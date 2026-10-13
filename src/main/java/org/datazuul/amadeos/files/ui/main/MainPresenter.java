package org.datazuul.amadeos.files.ui.main;

import com.google.inject.Inject;
import org.datazuul.amadeos.commons.desktop.DesktopLauncher;
import org.datazuul.amadeos.commons.swing.EdtExecutor;
import org.datazuul.amadeos.files.domain.FileEntry;
import org.datazuul.amadeos.files.domain.FilePreferences;
import org.datazuul.amadeos.files.domain.Progress;
import org.datazuul.amadeos.files.domain.Resolution;
import org.datazuul.amadeos.files.domain.Side;
import org.datazuul.amadeos.files.domain.SortKey;
import org.datazuul.amadeos.files.domain.SortOrder;
import org.datazuul.amadeos.files.repository.PreferencesRepository;
import org.datazuul.amadeos.files.service.FileOperationException;
import org.datazuul.amadeos.files.service.FileOperations;
import org.datazuul.amadeos.files.service.FileOperations.Summary;
import org.datazuul.amadeos.files.service.FileSystemService;
import org.datazuul.amadeos.files.service.Formats;
import org.datazuul.amadeos.files.service.Opener;
import org.datazuul.amadeos.files.service.OperationCancelledException;
import org.datazuul.amadeos.files.service.OperationListener;
import org.datazuul.amadeos.files.service.TrashService;
import org.datazuul.amadeos.files.service.TrashUnavailableException;
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
    }

    private record Listing(List<FileEntry> entries, long free) {
    }

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
     *                  right panel, as passed by the desktop entry
     */
    public void start(List<String> arguments) {
        try {
            preferences = preferencesRepository.load();
        } catch (RuntimeException e) {
            LOG.warn("Cannot read the preferences", e);
        }
        view.setShowHidden(preferences.showHidden());
        view.setTwoPanels(preferences.twoPanels());
        view.setActive(active);
        for (Side side : Side.values()) {
            int index = side.ordinal();
            Path start = null;
            String focus = null;
            if (arguments.size() > index) {
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
        load(side, panel.dir.getParent(), panel.dir.getFileName().toString());
    }

    public void navigate(Side side, Path dir) {
        load(side, dir, null);
    }

    public void sortBy(Side side, SortKey key) {
        PanelState panel = panels.get(side);
        panel.sort = panel.sort.toggle(key);
        panel.entries = panel.entries.stream().sorted(panel.sort.comparator()).toList();
        view.showDirectory(side, panel.dir, panel.entries, panel.sort, panel.current == null ? null : panel.current.name());
    }

    public void refresh() {
        for (Side side : Side.values()) reload(side);
    }

    private void reload(Side side) {
        PanelState panel = panels.get(side);
        if (panel.dir != null) load(side, panel.dir, panel.current == null ? null : panel.current.name());
    }

    private void load(Side side, Path dir, String focusName) {
        PanelState panel = panels.get(side);
        int generation = ++panel.generation;
        Path folder = dir.toAbsolutePath().normalize();
        boolean showHidden = preferences.showHidden();
        async(() -> new Listing(fileSystem.list(folder, showHidden), fileSystem.freeSpace(folder)), listing -> {
            if (generation != panel.generation) return;
            panel.dir = folder;
            panel.entries = listing.entries().stream().sorted(panel.sort.comparator()).toList();
            panel.free = listing.free();
            panel.selected = List.of();
            panel.current = null;
            view.showDirectory(side, folder, panel.entries, panel.sort, focusName);
            updateStatus(side);
        }, error -> {
            if (generation != panel.generation) return;
            view.showError(error);
            if (panel.dir == null) {
                // The start folder is gone: fall back to an existing parent or the home folder.
                Path fallback = existingParent(folder);
                if (fallback != null && !fallback.equals(folder)) load(side, fallback, null);
            }
        });
    }

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
        } else {
            text = Formats.count(items.size(), "item");
        }
        return panel.free >= 0 ? text + " · " + Formats.size(panel.free) + " free" : text;
    }

    // ---- opening --------------------------------------------------------------------------------

    /** F3: opens the file with its default application, or changes into a folder. */
    public void view() {
        FileEntry entry = panels.get(active).current;
        if (entry != null && entry.isDirectory()) open(active, entry);
        else if (entry != null) openFile(entry);
    }

    /** F4: opens the file in the Text Editor. */
    public void edit() {
        FileEntry entry = panels.get(active).current;
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

    /** Executables and launchers (.desktop, .sh, .jar, …) are not opened without asking. */
    static boolean canRunProgram(FileEntry entry) {
        return entry.executable() || (entry.name().lastIndexOf('.') > 0 && DesktopLauncher.isRisky(entry.name()));
    }

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
        PanelState source = panels.get(active);
        Path suggestion = preferences.twoPanels() ? panels.get(active.other()).dir : source.dir;
        String verb = move ? "Move" : "Copy";
        String answer = view.askText(verb, verb + " " + describe(items) + " to:",
                suggestion == null ? "" : suggestion.toString());
        if (answer == null) return;
        Path target = toPath(answer, source.dir);
        if (target == null) {
            view.showError("\"" + answer + "\" is not a valid folder.");
            return;
        }
        List<Path> paths = items.stream().map(FileEntry::path).toList();
        runOperation(move ? "Moving" : "Copying",
                listener -> move ? operations.move(paths, target, listener) : operations.copy(paths, target, listener),
                summary -> (move ? "Moved " : "Copied ") + Formats.count(summary.done(), "file") + " to " + target
                        + (summary.skipped() > 0 ? ", skipped " + summary.skipped() : "") + ".");
    }

    /** F7. */
    public void newFolder() {
        PanelState panel = panels.get(active);
        if (panel.dir == null) return;
        String name = view.askText("New Folder", "Name of the new folder in " + panel.dir + ":", "");
        if (name == null) return;
        Side side = active;
        async(() -> fileSystem.createDirectory(panel.dir, name), created -> {
            view.setStatus("Created " + created.getFileName() + ".");
            load(side, panel.dir, created.getFileName().toString());
            reloadOtherIfSame(side);
        }, view::showError);
    }

    /** F2 / Shift+F6. */
    public void rename() {
        PanelState panel = panels.get(active);
        FileEntry entry = panel.current;
        if (entry == null || entry.isParent()) {
            view.setStatus("Select a file to rename.");
            return;
        }
        String name = view.askText("Rename", "New name for " + entry.name() + ":", entry.name());
        if (name == null || name.equals(entry.name())) return;
        Side side = active;
        async(() -> fileSystem.rename(entry.path(), name), renamed -> {
            view.setStatus("Renamed " + entry.name() + " to " + renamed.getFileName() + ".");
            load(side, panel.dir, renamed.getFileName().toString());
            reloadOtherIfSame(side);
        }, view::showError);
    }

    /** F8 / Delete: to the trash, without asking, as it can be undone. */
    public void moveToTrash() {
        List<FileEntry> items = targets(active);
        if (items.isEmpty()) {
            view.setStatus("Nothing selected.");
            return;
        }
        List<Path> paths = items.stream().map(FileEntry::path).toList();
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
        if (!view.confirm("Delete Permanently", "Delete " + describe(items)
                + " permanently? This cannot be undone.")) {
            return;
        }
        deleteNow(items.stream().map(FileEntry::path).toList());
    }

    private void deleteNow(List<Path> paths) {
        runOperation("Deleting", listener -> operations.delete(paths, listener),
                summary -> "Deleted " + Formats.count(summary.done(), "file") + ".");
    }

    /** The marked lines, or the line under the cursor; never the parent entry. */
    private List<FileEntry> targets(Side side) {
        PanelState panel = panels.get(side);
        List<FileEntry> marked = panel.selected.stream().filter(e -> !e.isParent()).toList();
        if (!marked.isEmpty()) return marked;
        return panel.current != null && !panel.current.isParent() ? List.of(panel.current) : List.of();
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

    // ---- navigation and view --------------------------------------------------------------------

    public void goHome() {
        load(active, home, null);
    }

    public void goTrash() {
        Path files = trash.homeTrashFiles();
        if (!Files.isDirectory(files)) {
            view.setStatus("The trash is empty.");
            return;
        }
        load(active, files, null);
    }

    /** Ctrl+L: types a folder; {@code ~} is the home folder, relative paths start at the panel's folder. */
    public void goTo() {
        PanelState panel = panels.get(active);
        String answer = view.askText("Go To Folder", "Folder:", panel.dir == null ? "" : panel.dir.toString());
        if (answer == null || answer.isBlank()) return;
        Path target = toPath(answer, panel.dir);
        if (target == null) {
            view.showError("\"" + answer + "\" is not a valid folder.");
            return;
        }
        load(active, target, null);
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
