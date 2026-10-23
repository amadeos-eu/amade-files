package eu.amadeos.apps.files.ui.main;

import com.google.inject.Inject;
import eu.amadeos.commons.desktop.DesktopLauncher;
import eu.amadeos.commons.swing.EdtExecutor;
import eu.amadeos.apps.files.domain.ClipboardFiles;
import eu.amadeos.apps.files.domain.DropMode;
import eu.amadeos.apps.files.domain.FileEntry;
import eu.amadeos.apps.files.domain.FilePreferences;
import eu.amadeos.apps.files.domain.Place;
import eu.amadeos.apps.files.domain.Progress;
import eu.amadeos.apps.files.domain.Resolution;
import eu.amadeos.apps.files.domain.Side;
import eu.amadeos.apps.files.domain.SortKey;
import eu.amadeos.apps.files.domain.SortOrder;
import eu.amadeos.apps.files.domain.ViewMode;
import eu.amadeos.apps.files.repository.PreferencesRepository;
import eu.amadeos.apps.files.service.FileOperationException;
import eu.amadeos.apps.files.service.FileOperations;
import eu.amadeos.apps.files.service.FileOperations.Summary;
import eu.amadeos.apps.files.service.FileSystemService;
import eu.amadeos.apps.files.service.Formats;
import eu.amadeos.apps.files.service.Opener;
import eu.amadeos.apps.files.service.OperationCancelledException;
import eu.amadeos.apps.files.service.OperationListener;
import eu.amadeos.apps.files.service.PathCompletion;
import eu.amadeos.apps.files.service.TrashService;
import eu.amadeos.apps.files.service.TrashUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Behaviour of the File Manager. Runs on the EDT; reading folders and file operations go to the
 * background executor and come back to the EDT. A folder listing that arrives after the user moved
 * on is dropped.
 *
 * <p>In the tree view the left panel shows the contents of the folder chosen in the tree, and the
 * right panel waits in the background for the two-panel view. While the tree has the keyboard focus,
 * the commands work on the folder chosen in it instead of the lines of the panel.
 */
public final class MainPresenter {
    private static final Logger LOG = LoggerFactory.getLogger(MainPresenter.class);

    private final MainView view;
    private final FileSystemService fileSystem;
    private final FileOperations operations;
    private final TrashService trash;
    private final Opener opener;
    private final PreferencesRepository preferencesRepository;
    private final ExecutorService executor;
    private final Executor edt;
    private final Path home;

    private final Map<Side, PanelState> panels = new EnumMap<>(Side.class);
    private Side active = Side.LEFT;
    private FilePreferences preferences = FilePreferences.DEFAULTS;
    private List<Place> places = List.of();
    /** The folder chosen in the tree while the tree has the keyboard focus; null otherwise. */
    private Path treeTarget;
    private boolean busy;

    /** What one panel shows. */
    private static final class PanelState {
        Path dir;
        List<FileEntry> entries = List.of();
        SortOrder sort = SortOrder.DEFAULT;
        List<FileEntry> selected = List.of();
        FileEntry current;
        long free = -1;
        int generation;
        /** The folder is the {@code files} folder of a trash. */
        boolean inTrash;
    }

    private record Listing(List<FileEntry> entries, long free, boolean inTrash) {
    }

    /** The argument that opens the trash, as the front panel's Trash Can passes it. */
    static final String TRASH_ARGUMENT = "trash:";

    @Inject
    public MainPresenter(MainView view, FileSystemService fileSystem, FileOperations operations, TrashService trash,
                         Opener opener, PreferencesRepository preferencesRepository, ExecutorService executor,
                         EdtExecutor edt) {
        this(view, fileSystem, operations, trash, opener, preferencesRepository, executor, edt,
                Path.of(System.getProperty("user.home")));
    }

    MainPresenter(MainView view, FileSystemService fileSystem, FileOperations operations, TrashService trash,
                  Opener opener, PreferencesRepository preferencesRepository, ExecutorService executor,
                  Executor edt, Path home) {
        this.view = view;
        this.fileSystem = fileSystem;
        this.operations = operations;
        this.trash = trash;
        this.opener = opener;
        this.preferencesRepository = preferencesRepository;
        this.executor = executor;
        this.edt = edt;
        this.home = home;
        for (Side side : Side.values()) panels.put(side, new PanelState());
    }

    // ---- start and exit -------------------------------------------------------------------------

    /**
     * @param arguments folders (or files, whose folder is shown with the file selected) for the left and
     *                  right panel, as passed by the desktop entry; {@code trash:} is the home trash
     */
    public void start(List<String> arguments) {
        try {
            preferences = preferencesRepository.load();
        } catch (RuntimeException e) {
            LOG.warn("Cannot read the preferences", e);
        }
        view.setShowHidden(preferences.showHidden());
        view.setTwoPanels(preferences.twoPanels());
        view.setViewMode(preferences.viewMode());
        view.setActive(active);
        refreshPlaces();
        for (Side side : Side.values()) {
            int index = side.ordinal();
            Path start = null;
            String focus = null;
            if (arguments.size() > index && arguments.get(index).strip().startsWith(TRASH_ARGUMENT)) {
                try {
                    start = trash.ensureHomeTrash();
                } catch (TrashUnavailableException e) {
                    view.showError(e.getMessage());
                }
            } else if (arguments.size() > index) {
                Path argument = toPath(arguments.get(index), home);
                if (argument != null && Files.isDirectory(argument)) {
                    start = argument;
                } else if (argument != null && Files.exists(argument) && argument.getParent() != null) {
                    start = argument.getParent();
                    focus = argument.getFileName().toString();
                }
            }
            if (start == null) {
                Path remembered = preferences.dir(side);
                start = remembered != null && Files.isDirectory(remembered) ? remembered : home;
            }
            load(side, start, focus);
        }
    }

    public void exit() {
        if (busy && !view.confirm("Quit", "A file operation is still running. Quit anyway?")) return;
        try {
            preferencesRepository.save(preferences.withDirs(panels.get(Side.LEFT).dir, panels.get(Side.RIGHT).dir));
        } catch (RuntimeException e) {
            LOG.warn("Cannot save the preferences", e);
        }
        view.dispose();
        executor.shutdownNow();
    }

    // ---- panels ---------------------------------------------------------------------------------

    public void panelActivated(Side side) {
        treeTarget = null;
        if (side == active) return;
        active = side;
        view.setActive(side);
    }

    /** The cursor or the marked lines of a panel changed. */
    public void selectionChanged(Side side, List<FileEntry> selected, FileEntry current) {
        PanelState panel = panels.get(side);
        panel.selected = List.copyOf(selected);
        panel.current = current;
        updateStatus(side);
    }

    /** Enter or double-click on a line. */
    public void open(Side side, FileEntry entry) {
        if (entry == null) return;
        if (entry.isParent()) {
            up(side);
        } else if (entry.isDirectory()) {
            load(side, entry.path(), null);
        } else if (entry.kind() == FileEntry.Kind.FILE) {
            openFile(entry);
        } else {
            view.setStatus(entry.name() + " cannot be opened.");
        }
    }

    public void up(Side side) {
        PanelState panel = panels.get(side);
        if (panel.dir == null || panel.dir.getParent() == null) return;
        // Above the trash lie its files and info folders, which are not for browsing.
        if (panel.inTrash) {
            load(side, home, null);
            return;
        }
        load(side, panel.dir.getParent(), panel.dir.getFileName().toString());
    }

    public void navigate(Side side, Path dir) {
        load(side, dir, null);
    }

    public void sortBy(Side side, SortKey key) {
        PanelState panel = panels.get(side);
        panel.sort = panel.sort.toggle(key);
        panel.entries = panel.entries.stream().sorted(panel.sort.comparator()).toList();
        show(side, panel.current == null ? null : panel.current.name());
    }

    public void refresh() {
        for (Side side : Side.values()) reload(side);
        view.refreshFolders();
        refreshPlaces();
    }

    /** The panel's lines; the tree view leaves out the parent line, as the tree leads up. */
    private void show(Side side, String focusName) {
        PanelState panel = panels.get(side);
        List<FileEntry> lines = treeView() && side == Side.LEFT
                ? panel.entries.stream().filter(e -> !e.isParent()).toList() : panel.entries;
        view.showDirectory(side, panel.dir, lines, panel.sort, focusName);
    }

    private boolean treeView() {
        return preferences.viewMode() == ViewMode.TREE;
    }

    private void reload(Side side) {
        PanelState panel = panels.get(side);
        if (panel.dir != null) load(side, panel.dir, panel.current == null ? null : panel.current.name());
    }

    // --8<-- [start:load]
    private void load(Side side, Path dir, String focusName) {
        PanelState panel = panels.get(side);
        int generation = ++panel.generation;
        Path folder = dir.toAbsolutePath().normalize();
        boolean showHidden = preferences.showHidden();
        async(() -> {
            // The trash shows everything that is in it, with where it came from; no parent line.
            boolean inTrash = trash.isTrashFiles(folder);
            List<FileEntry> entries = fileSystem.list(folder, showHidden || inTrash);
            if (inTrash) {
                entries = entries.stream().filter(e -> !e.isParent()).map(e -> e.withTrash(trash.info(e.path()))).toList();
            }
            return new Listing(entries, fileSystem.freeSpace(folder), inTrash);
        }, listing -> {
            if (generation != panel.generation) return;
            panel.dir = folder;
            panel.inTrash = listing.inTrash();
            panel.entries = listing.entries().stream().sorted(panel.sort.comparator()).toList();
            panel.free = listing.free();
            panel.selected = List.of();
            panel.current = null;
            view.setTrashView(side, panel.inTrash);
            show(side, focusName);
            updateStatus(side);
        }, error -> {
            if (generation != panel.generation) return;
            if (panel.dir != null && !Files.exists(folder)) {
                // The folder was trashed, renamed or unmounted while shown: its nearest existing parent.
                Path fallback = existingParent(folder);
                if (fallback != null) {
                    load(side, fallback, null);
                    return;
                }
            }
            view.showError(error);
            if (panel.dir == null) {
                // The start folder is gone: fall back to an existing parent or the home folder.
                Path fallback = existingParent(folder);
                if (fallback != null && !fallback.equals(folder)) load(side, fallback, null);
            }
        });
    }
    // --8<-- [end:load]

    private Path existingParent(Path dir) {
        for (Path p = dir.getParent(); p != null; p = p.getParent()) {
            if (Files.isDirectory(p)) return p;
        }
        return Files.isDirectory(home) ? home : null;
    }

    private void updateStatus(Side side) {
        PanelState panel = panels.get(side);
        view.setPanelStatus(side, statusText(panel));
    }

    private static String statusText(PanelState panel) {
        List<FileEntry> items = panel.entries.stream().filter(e -> !e.isParent()).toList();
        List<FileEntry> selected = panel.selected.stream().filter(e -> !e.isParent()).toList();
        String text;
        if (selected.size() > 1) {
            long bytes = selected.stream().mapToLong(FileEntry::size).sum();
            text = selected.size() + " of " + items.size() + " selected, " + Formats.size(bytes);
        } else if (panel.current != null && !panel.current.isParent()) {
            FileEntry e = panel.current;
            text = e.isDirectory() ? e.name() + "/" : e.name() + ", " + Formats.size(e.size());
            if (e.trash() != null) text += ", from " + e.trash().originalFolder();
        } else if (panel.inTrash && items.isEmpty()) {
            text = "The trash is empty.";
        } else {
            text = Formats.count(items.size(), "item");
        }
        return panel.free >= 0 ? text + " · " + Formats.size(panel.free) + " free" : text;
    }

    // ---- opening --------------------------------------------------------------------------------

    /** F3: opens the file with its default application, or changes into a folder. */
    public void view() {
        FileEntry entry = focused();
        if (entry != null && entry.isDirectory()) open(active, entry);
        else if (entry != null) openFile(entry);
    }

    /** F4: opens the file in the Text Editor. */
    public void edit() {
        FileEntry entry = focused();
        if (entry == null || entry.kind() != FileEntry.Kind.FILE) {
            view.setStatus("Select a file to edit.");
            return;
        }
        async(() -> {
            opener.edit(entry.path());
            return null;
        }, ignored -> view.setStatus("Opened " + entry.name() + " in the Text Editor."), view::showError);
    }

    private void openFile(FileEntry entry) {
        if (canRunProgram(entry) && !view.confirm("Open", entry.name() + " can run a program.\nOpen it anyway?")) {
            return;
        }
        async(() -> {
            opener.open(entry.path());
            return null;
        }, ignored -> view.setStatus("Opened " + entry.name() + "."), view::showError);
    }

    // --8<-- [start:can-run]
    /** Executables and launchers (.desktop, .sh, .jar, …) are not opened without asking. */
    static boolean canRunProgram(FileEntry entry) {
        return entry.executable() || (entry.name().lastIndexOf('.') > 0 && DesktopLauncher.isRisky(entry.name()));
    }
    // --8<-- [end:can-run]

    // ---- file operations ------------------------------------------------------------------------

    /** F5. */
    public void copy() {
        transfer(false);
    }

    /** F6. */
    public void move() {
        transfer(true);
    }

    private void transfer(boolean move) {
        List<FileEntry> items = targets(active);
        if (items.isEmpty()) {
            view.setStatus("Nothing selected.");
            return;
        }
        if (move && refusePlaces(items)) return;
        PanelState source = panels.get(active);
        Path suggestion = !treeView() && preferences.twoPanels() ? panels.get(active.other()).dir : source.dir;
        String verb = move ? "Move" : "Copy";
        String answer = view.askText(verb, verb + " " + describe(items) + " to:",
                suggestion == null ? "" : suggestion.toString());
        if (answer == null) return;
        Path target = toPath(answer, source.dir);
        if (target == null) {
            view.showError("\"" + answer + "\" is not a valid folder.");
            return;
        }
        transferPaths(items.stream().map(FileEntry::path).toList(), target, move, () -> {
        });
    }

    private void transferPaths(List<Path> paths, Path target, boolean move, Runnable afterwards) {
        runOperation(move ? "Moving" : "Copying",
                listener -> move ? operations.move(paths, target, listener) : operations.copy(paths, target, listener),
                summary -> {
                    afterwards.run();
                    return (move ? "Moved " : "Copied ") + Formats.count(summary.done(), "file") + " to " + target
                            + (summary.skipped() > 0 ? ", skipped " + summary.skipped() : "") + ".";
                });
    }

    /** F7. */
    public void newFolder() {
        PanelState panel = panels.get(active);
        Path parent = treeTarget != null ? treeTarget : panel.dir;
        if (parent == null) return;
        if (trash.isTrashFiles(parent)) {
            view.setStatus("New folders cannot be made in the trash.");
            return;
        }
        String name = view.askText("New Folder", "Name of the new folder in " + parent + ":", "");
        if (name == null) return;
        Side side = active;
        async(() -> fileSystem.createDirectory(parent, name), created -> {
            view.setStatus("Created " + created.getFileName() + ".");
            if (parent.equals(panel.dir)) load(side, panel.dir, created.getFileName().toString());
            reloadOtherIfSame(side);
            view.refreshFolders();
        }, view::showError);
    }

    /** F2 / Shift+F6. */
    public void rename() {
        PanelState panel = panels.get(active);
        FileEntry entry = focused();
        if (entry == null || entry.isParent()) {
            view.setStatus("Select a file to rename.");
            return;
        }
        if (refusePlaces(List.of(entry))) return;
        String name = view.askText("Rename", "New name for " + entry.name() + ":", entry.name());
        if (name == null || name.equals(entry.name())) return;
        Side side = active;
        async(() -> fileSystem.rename(entry.path(), name), renamed -> {
            view.setStatus("Renamed " + entry.name() + " to " + renamed.getFileName() + ".");
            // A renamed folder that is shown, or chosen in the tree, is followed to its new name.
            if (treeTarget != null && treeTarget.startsWith(entry.path())) {
                treeTarget = renamed.resolve(entry.path().relativize(treeTarget));
            }
            for (Side s : Side.values()) {
                PanelState p = panels.get(s);
                if (s != side && p.dir != null && p.dir.startsWith(entry.path())) {
                    load(s, renamed.resolve(entry.path().relativize(p.dir)), null);
                }
            }
            Path dir = panel.dir.startsWith(entry.path()) ? renamed.resolve(entry.path().relativize(panel.dir)) : panel.dir;
            load(side, dir, renamed.getFileName().toString());
            reloadOtherIfSame(side);
            view.refreshFolders();
        }, view::showError);
    }

    /** F8 / Delete: to the trash, without asking, as it can be undone. In the trash: for good, after asking. */
    public void moveToTrash() {
        if (panels.get(active).inTrash && treeTarget == null) {
            deletePermanently();
            return;
        }
        List<FileEntry> items = targets(active);
        if (items.isEmpty()) {
            view.setStatus("Nothing selected.");
            return;
        }
        if (refusePlaces(items)) return;
        trashPaths(items.stream().map(FileEntry::path).toList());
    }

    private void trashPaths(List<Path> paths) {
        if (paths.stream().anyMatch(p -> trash.isTrashFiles(p.getParent() == null ? p : p.getParent()))) {
            view.setStatus("Files in the trash are deleted with Delete Permanently.");
            return;
        }
        if (treeTarget != null && paths.stream().anyMatch(treeTarget::startsWith)) treeTarget = null;
        List<Path> remaining = new ArrayList<>(paths);
        async(() -> {
            try {
                for (Path path : paths) {
                    trash.trash(path);
                    remaining.remove(path);
                }
                return null;
            } catch (TrashUnavailableException e) {
                return e.getMessage();
            }
        }, unavailable -> {
            int trashed = paths.size() - remaining.size();
            if (trashed > 0) view.setStatus("Moved " + Formats.count(trashed, "item") + " to the trash.");
            refresh();
            if (unavailable != null && view.confirm("Delete Permanently", unavailable + "\n\nDelete "
                    + describePaths(remaining) + " permanently instead? This cannot be undone.")) {
                deleteNow(remaining);
            }
        }, error -> {
            view.showError(error);
            refresh();
        });
    }

    /** Shift+F8 / Shift+Delete. */
    public void deletePermanently() {
        List<FileEntry> items = targets(active);
        if (items.isEmpty()) {
            view.setStatus("Nothing selected.");
            return;
        }
        if (refusePlaces(items)) return;
        if (!view.confirm("Delete Permanently", "Delete " + describe(items)
                + " permanently? This cannot be undone.")) {
            return;
        }
        deleteNow(items.stream().map(FileEntry::path).toList());
    }

    private void deleteNow(List<Path> paths) {
        boolean inTrash = panels.get(active).inTrash;
        runOperation("Deleting", listener -> {
            Summary summary = operations.delete(paths, listener);
            if (inTrash) trash.forget(paths);
            return summary;
        }, summary -> "Deleted " + Formats.count(summary.done(), "file") + ".");
    }

    // ---- the trash ------------------------------------------------------------------------------

    /** Puts the marked trashed files back where they came from. */
    public void restore() {
        PanelState panel = panels.get(active);
        if (!panel.inTrash) {
            view.setStatus("Restore works in the trash (Go → Trash).");
            return;
        }
        List<FileEntry> items = targets(active);
        if (items.isEmpty()) {
            view.setStatus("Nothing selected.");
            return;
        }
        List<Path> paths = items.stream().map(FileEntry::path).toList();
        List<Path> restored = new ArrayList<>();
        async(() -> {
            try {
                for (Path path : paths) restored.add(trash.restore(path));
                return null;
            } catch (FileOperationException e) {
                return e.getMessage();
            }
        }, error -> {
            if (restored.size() == 1) view.setStatus("Restored " + restored.getFirst() + ".");
            else if (!restored.isEmpty()) view.setStatus("Restored " + Formats.count(restored.size(), "item") + ".");
            refresh();
            if (error != null) view.showError(error);
        }, error -> {
            view.showError(error);
            refresh();
        });
    }

    /** Deletes everything in the trash shown in the active panel, or in the home trash, after asking. */
    public void emptyTrash() {
        PanelState panel = panels.get(active);
        Path files;
        List<Path> paths;
        if (panel.inTrash) {
            files = panel.dir;
            paths = panel.entries.stream().map(FileEntry::path).toList();
        } else {
            try {
                files = trash.ensureHomeTrash();
                paths = fileSystem.list(files, true).stream().filter(e -> !e.isParent()).map(FileEntry::path).toList();
            } catch (FileOperationException e) {
                view.showError(e.getMessage());
                return;
            }
        }
        if (paths.isEmpty()) {
            view.setStatus("The trash is empty.");
            return;
        }
        if (!view.confirm("Empty Trash", "Delete " + Formats.count(paths.size(), "item")
                + " in the trash permanently? This cannot be undone.")) {
            return;
        }
        Path trashFiles = files;
        runOperation("Emptying the trash", listener -> {
            Summary summary = operations.delete(paths, listener);
            trash.clearInfo(trashFiles);
            return summary;
        }, summary -> "Emptied the trash: deleted " + Formats.count(summary.done(), "file") + ".");
    }

    /**
     * The folder chosen in the tree while the tree has the focus; otherwise the marked lines, or the
     * line under the cursor; never the parent entry.
     */
    private List<FileEntry> targets(Side side) {
        if (treeTarget != null) return List.of(fileSystem.describe(treeTarget));
        PanelState panel = panels.get(side);
        List<FileEntry> marked = panel.selected.stream().filter(e -> !e.isParent()).toList();
        if (!marked.isEmpty()) return marked;
        return panel.current != null && !panel.current.isParent() ? List.of(panel.current) : List.of();
    }

    /** The folder chosen in the tree while the tree has the focus, otherwise the line under the cursor. */
    private FileEntry focused() {
        return treeTarget != null ? fileSystem.describe(treeTarget) : panels.get(active).current;
    }

    /** Home, the trash, the file system and mounted media stay where they are. */
    private boolean refusePlaces(List<FileEntry> items) {
        for (FileEntry item : items) {
            if (isPlace(item.path())) {
                view.setStatus(placeName(item.path()) + " cannot be moved, renamed or deleted.");
                return true;
            }
        }
        return false;
    }

    private boolean isPlace(Path path) {
        return path.getParent() == null || places.stream().anyMatch(p -> p.path().equals(path));
    }

    private String placeName(Path path) {
        return places.stream().filter(p -> p.path().equals(path)).map(Place::name).findFirst()
                .orElse(path.getFileName() == null ? path.toString() : path.getFileName().toString());
    }

    private static String describe(List<FileEntry> items) {
        return items.size() == 1 ? "\"" + items.getFirst().name() + "\"" : Formats.count(items.size(), "item");
    }

    private static String describePaths(List<Path> paths) {
        return paths.size() == 1 ? "\"" + paths.getFirst().getFileName() + "\"" : Formats.count(paths.size(), "item");
    }

    private void reloadOtherIfSame(Side side) {
        PanelState panel = panels.get(side);
        PanelState other = panels.get(side.other());
        if (other.dir != null && other.dir.equals(panel.dir)) reload(side.other());
    }

    // --8<-- [start:run-operation]
    /**
     * Runs a long operation with a progress dialog. Only one runs at a time; afterwards both panels are
     * read again.
     */
    private void runOperation(String title, Function<OperationListener, Summary> work, Function<Summary, String> done) {
        if (busy) {
            view.showError("Another file operation is still running.");
            return;
        }
        busy = true;
        AtomicBoolean cancelled = new AtomicBoolean();
        ProgressView progress = view.showProgress(title, () -> cancelled.set(true));
        OperationListener listener = new OperationListener() {
            private Resolution forAll;

            @Override
            public void progress(Progress p) {
                edt.execute(() -> progress.update(p));
            }

            @Override
            public Resolution conflict(Path source, Path target) {
                if (forAll != null) return forAll;
                ConflictChoice choice = onEdtAndWait(() -> view.askConflict(fileSystem.describe(source),
                        fileSystem.describe(target)));
                if (choice == null) return Resolution.CANCEL;
                if (choice.all()) forAll = choice.resolution();
                return choice.resolution();
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        };
        executor.execute(() -> {
            String message;
            String error = null;
            try {
                message = done.apply(work.apply(listener));
            } catch (OperationCancelledException e) {
                message = title + " cancelled.";
            } catch (FileOperationException e) {
                message = null;
                error = e.getMessage();
            } catch (RuntimeException e) {
                LOG.error("{} failed", title, e);
                message = null;
                error = title + " failed: " + e;
            }
            String finalMessage = message;
            String finalError = error;
            edt.execute(() -> {
                busy = false;
                progress.close();
                if (finalError != null) view.showError(finalError);
                else view.setStatus(finalMessage);
                refresh();
            });
        });
    }

    private <T> T onEdtAndWait(Supplier<T> question) {
        return CompletableFuture.supplyAsync(question, edt).join();
    }
    // --8<-- [end:run-operation]

    // ---- navigation and view --------------------------------------------------------------------

    public void goHome() {
        load(active, home, null);
    }

    public void goTrash() {
        try {
            load(active, trash.ensureHomeTrash(), null);
        } catch (TrashUnavailableException e) {
            view.showError(e.getMessage());
        }
    }

    /**
     * A path typed into the path bar (Ctrl+L); {@code ~} is the home folder, relative paths start at the
     * panel's folder. A file opens its folder with the file under the cursor.
     */
    public void goTo(Side side, String text) {
        if (text == null || text.isBlank()) return;
        PanelState panel = panels.get(side);
        Path target = toPath(text, panel.dir);
        if (target == null) {
            view.showError("\"" + text + "\" is not a valid folder.");
            return;
        }
        if (!Files.isDirectory(target) && Files.exists(target) && target.getParent() != null) {
            load(side, target.getParent(), target.getFileName().toString());
        } else {
            load(side, target, null);
        }
    }

    /** Tab in the path bar. */
    public String completePath(Side side, String text) {
        Path base = panels.get(side).dir;
        return PathCompletion.complete(text, base != null ? base : home, home);
    }

    /** Copy Path in the context menu of the path bar. */
    public void copyPath(Path path) {
        view.copyToClipboard(path.toString());
        view.setStatus("Copied " + path + " to the clipboard.");
    }

    /** The other panel shows the same folder as the active one. */
    public void sameFolderInOtherPanel() {
        PanelState panel = panels.get(active);
        if (panel.dir != null) load(active.other(), panel.dir, null);
    }

    public void swapPanels() {
        Path left = panels.get(Side.LEFT).dir;
        Path right = panels.get(Side.RIGHT).dir;
        if (left == null || right == null) return;
        load(Side.LEFT, right, null);
        load(Side.RIGHT, left, null);
    }

    public void setShowHidden(boolean showHidden) {
        preferences = preferences.withShowHidden(showHidden);
        view.setShowHidden(showHidden);
        refresh();
    }

    /** View → Tree or Two Panels. */
    public void setViewMode(ViewMode mode) {
        if (mode == preferences.viewMode()) {
            view.setViewMode(mode);
            return;
        }
        preferences = preferences.withViewMode(mode);
        treeTarget = null;
        view.setViewMode(mode);
        if (mode == ViewMode.TREE && active != Side.LEFT) {
            // The tree continues with the folder that was in front.
            Path dir = panels.get(active).dir;
            panelActivated(Side.LEFT);
            if (dir != null) load(Side.LEFT, dir, null);
        }
        PanelState left = panels.get(Side.LEFT);
        if (left.dir != null) show(Side.LEFT, left.current == null ? null : left.current.name());
    }

    /** Open in Two Panels from a folder's context menu. */
    public void openInPanels(Path dir) {
        setViewMode(ViewMode.PANELS);
        panelActivated(Side.LEFT);
        load(Side.LEFT, dir, null);
    }

    public void setTwoPanels(boolean twoPanels) {
        preferences = preferences.withTwoPanels(twoPanels);
        view.setTwoPanels(twoPanels);
        if (!twoPanels && active != Side.LEFT) panelActivated(Side.LEFT);
    }

    public void copyPaths() {
        List<FileEntry> items = targets(active);
        if (items.isEmpty()) return;
        view.copyToClipboard(items.stream().map(e -> e.path().toString()).collect(Collectors.joining("\n")));
        view.setStatus("Copied " + Formats.count(items.size(), "path") + " to the clipboard.");
    }

    // ---- clipboard ------------------------------------------------------------------------------

    /** Ctrl+X: Paste moves the files. */
    public void cutFiles() {
        putOnClipboard(true);
    }

    /** Ctrl+C: Paste copies the files. */
    public void copyFiles() {
        putOnClipboard(false);
    }

    private void putOnClipboard(boolean cut) {
        List<FileEntry> items = targets(active);
        if (items.isEmpty()) {
            view.setStatus("Nothing selected.");
            return;
        }
        if (cut && refusePlaces(items)) return;
        view.putFilesOnClipboard(new ClipboardFiles(items.stream().map(FileEntry::path).toList(), cut));
        view.setStatus((cut ? "Cut " : "Copied ") + describe(items) + ". Paste with Ctrl+V.");
    }

    /** Ctrl+V: into the folder chosen in the tree, or the folder of the active panel. */
    public void paste() {
        Path dir = treeTarget != null ? treeTarget : panels.get(active).dir;
        if (dir != null) pasteInto(dir);
    }

    public void pasteInto(Path dir) {
        ClipboardFiles files = view.filesOnClipboard();
        if (files == null || files.paths().isEmpty()) {
            view.setStatus("There are no files on the clipboard.");
            return;
        }
        if (trash.isTrashFiles(dir)) {
            view.setStatus("Paste does not work in the trash; use Move to Trash.");
            return;
        }
        // Cut files are moved once; afterwards the clipboard no longer offers them.
        transferPaths(files.paths(), dir, files.cut(), () -> {
            if (files.cut()) edt.execute(() -> view.putFilesOnClipboard(new ClipboardFiles(List.of(), false)));
        });
    }

    // ---- drag and drop --------------------------------------------------------------------------

    /** Files dropped on a folder: moved or copied, or trashed when dropped on the trash. */
    public void drop(List<Path> sources, Path target, DropMode mode) {
        if (sources.isEmpty() || target == null || describeDrop(sources, target, mode) == null) return;
        if (sources.stream().anyMatch(this::isPlace)) {
            view.setStatus(placeName(sources.stream().filter(this::isPlace).findFirst().orElseThrow())
                    + " cannot be moved, renamed or deleted.");
            return;
        }
        if (trash.isTrashFiles(target)) {
            trashPaths(sources);
            return;
        }
        transferPaths(sources, target, dropMoves(sources, target, mode), () -> {
        });
    }

    /**
     * What dropping would do, shown next to the mouse while dragging: {@code Move to "Projekte"}; null
     * where the files cannot go (into themselves, or back where they are).
     *
     * @param sources the dragged files; empty while another program does not tell them yet
     */
    public String describeDrop(List<Path> sources, Path target, DropMode mode) {
        if (target == null) return null;
        for (Path source : sources) {
            if (target.startsWith(source) || isPlace(source)) return null;
        }
        if (trash.isTrashFiles(target)) {
            boolean fromTrash = sources.stream().anyMatch(p -> p.getParent() != null && trash.isTrashFiles(p.getParent()));
            return fromTrash ? null : "Move to Trash";
        }
        if (mode != DropMode.COPY && !sources.isEmpty() && sources.stream().allMatch(p -> target.equals(p.getParent()))) {
            return null;
        }
        boolean move = sources.isEmpty() ? mode == DropMode.MOVE : dropMoves(sources, target, mode);
        return (move ? "Move to \"" : "Copy to \"") + placeName(target) + "\"";
    }

    private boolean dropMoves(List<Path> sources, Path target, DropMode mode) {
        return mode == DropMode.MOVE || mode == DropMode.AUTO && fileSystem.sameFileSystem(sources.getFirst(), target);
    }

    // ---- folder tree ----------------------------------------------------------------------------

    /** A folder was chosen in the tree: the contents side shows it, and the commands work on it. */
    public void folderSelected(Path dir) {
        treeTarget = dir;
        if (active != Side.LEFT) {
            active = Side.LEFT;
            view.setActive(Side.LEFT);
        }
        PanelState panel = panels.get(Side.LEFT);
        if (!dir.equals(panel.dir)) load(Side.LEFT, dir, null);
    }

    /** The tree got the keyboard focus. */
    public void treeFocused(Path dir) {
        if (dir != null) treeTarget = dir;
    }

    /** A folder of the tree is opened for the first time, or read again: its sub-folders. */
    public void expandFolder(Path dir) {
        boolean showHidden = preferences.showHidden();
        async(() -> fileSystem.folders(dir, showHidden), folders -> view.showFolders(dir, folders),
                error -> view.showFolders(dir, List.of()));
    }

    /** Home, trash, file system and the mounted media; called again now and then for USB sticks. */
    public void refreshPlaces() {
        async(() -> fileSystem.places(home, trash.homeTrashFiles()), found -> {
            if (found.equals(places)) return;
            places = found;
            view.setPlaces(found);
        }, error -> LOG.debug("Cannot read the places: {}", error));
    }

    /** The folder of the active panel, for the window title. */
    public Path activeDir() {
        return panels.get(active).dir;
    }

    public Side activeSide() {
        return active;
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** Typed path: {@code ~} expands to the home folder, relative paths start at {@code base}. */
    Path toPath(String text, Path base) {
        String value = text.strip();
        if (value.isEmpty()) return null;
        try {
            Path path;
            if (value.equals("~")) path = home;
            else if (value.startsWith("~/")) path = home.resolve(value.substring(2));
            else path = Path.of(value);
            if (!path.isAbsolute()) path = (base != null ? base : home).resolve(path);
            return path.normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private <T> void async(Supplier<T> task, Consumer<T> onSuccess, Consumer<String> onError) {
        executor.execute(() -> {
            try {
                T result = task.get();
                edt.execute(() -> onSuccess.accept(result));
            } catch (FileOperationException e) {
                edt.execute(() -> onError.accept(e.getMessage()));
            } catch (RuntimeException e) {
                LOG.error("Unexpected failure", e);
                edt.execute(() -> onError.accept("Unexpected error: " + e));
            }
        });
    }
}
