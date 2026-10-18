package eu.amadeos.apps.files.ui.main;

import eu.amadeos.commons.config.XdgDirectories;
import eu.amadeos.apps.files.domain.FileEntry;
import eu.amadeos.apps.files.domain.FilePreferences;
import eu.amadeos.apps.files.domain.Progress;
import eu.amadeos.apps.files.domain.Resolution;
import eu.amadeos.apps.files.domain.Side;
import eu.amadeos.apps.files.domain.SortKey;
import eu.amadeos.apps.files.domain.SortOrder;
import eu.amadeos.apps.files.repository.PreferencesRepository;
import eu.amadeos.apps.files.service.FileOperations;
import eu.amadeos.apps.files.service.FileSystemService;
import eu.amadeos.apps.files.service.Opener;
import eu.amadeos.apps.files.service.TrashService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Runs the presenter with real services on temporary folders, a same-thread executor and a fake view,
 * so every asynchronous step completes before the action returns. No display needed.
 */
class MainPresenterTest {
    @TempDir
    Path dir;

    private Path home;
    private Path other;
    private final FakeView view = new FakeView();
    private final PreferencesRepository preferences = mock(PreferencesRepository.class);
    private final Opener opener = mock(Opener.class);
    private TrashService trash;
    private MainPresenter presenter;

    @BeforeEach
    void setUp() throws IOException {
        home = Files.createDirectory(dir.resolve("home"));
        other = Files.createDirectory(dir.resolve("other"));
        Files.writeString(home.resolve("b.txt"), "bb");
        Files.writeString(home.resolve("a.txt"), "a");
        Files.createDirectory(home.resolve("Docs"));
        Files.writeString(home.resolve(".hidden"), "h");
        when(preferences.load()).thenReturn(FilePreferences.DEFAULTS.withDirs(home, other));
        trash = new TrashService(new XdgDirectories(
                Map.of("XDG_DATA_HOME", dir.resolve("data").toString()), home.toString()));
        presenter = new MainPresenter(view, new FileSystemService(), new FileOperations(), trash, opener,
                preferences, new DirectExecutorService(), Runnable::run, home);
    }

    private FileEntry entry(Side side, String name) {
        return view.entries.get(side).stream().filter(e -> e.name().equals(name)).findFirst().orElseThrow();
    }

    private List<String> names(Side side) {
        return view.entries.get(side).stream().map(FileEntry::name).toList();
    }

    /** Puts the cursor on a line, as the panel reports it. */
    private void cursor(Side side, String name) {
        presenter.selectionChanged(side, List.of(), entry(side, name));
    }

    @Test
    void startsWithTheRememberedFoldersSorted() {
        presenter.start(List.of());
        assertEquals(home, view.dirs.get(Side.LEFT));
        assertEquals(other, view.dirs.get(Side.RIGHT));
        assertEquals(List.of("..", "Docs", "a.txt", "b.txt"), names(Side.LEFT));
        assertTrue(view.status.get(Side.LEFT).startsWith("3 items · "), view.status.get(Side.LEFT));
        assertEquals(Side.LEFT, view.active);
        assertTrue(view.twoPanels);
    }

    @Test
    void fileArgumentOpensItsFolderWithTheFileUnderTheCursor() {
        presenter.start(List.of(home.resolve("b.txt").toString(), "~/Docs"));
        assertEquals(home, view.dirs.get(Side.LEFT));
        assertEquals("b.txt", view.focus.get(Side.LEFT));
        assertEquals(home.resolve("Docs"), view.dirs.get(Side.RIGHT));
    }

    @Test
    void entersFoldersAndGoesBackUpToThePreviousFolder() {
        presenter.start(List.of());
        presenter.open(Side.LEFT, entry(Side.LEFT, "Docs"));
        assertEquals(home.resolve("Docs"), view.dirs.get(Side.LEFT));
        presenter.open(Side.LEFT, entry(Side.LEFT, ".."));
        assertEquals(home, view.dirs.get(Side.LEFT));
        assertEquals("Docs", view.focus.get(Side.LEFT));
    }

    @Test
    void sortingTogglesAndKeepsTheCursor() {
        presenter.start(List.of());
        cursor(Side.LEFT, "a.txt");
        presenter.sortBy(Side.LEFT, SortKey.NAME);
        assertEquals(List.of("..", "Docs", "b.txt", "a.txt"), names(Side.LEFT));
        assertEquals("a.txt", view.focus.get(Side.LEFT));
        presenter.sortBy(Side.LEFT, SortKey.SIZE);
        assertEquals(List.of("..", "Docs", "a.txt", "b.txt"), names(Side.LEFT));
    }

    @Test
    void showHiddenFiles() {
        presenter.start(List.of());
        presenter.setShowHidden(true);
        assertTrue(names(Side.LEFT).contains(".hidden"));
    }

    @Test
    void copiesMarkedFilesToTheOtherPanel() throws IOException {
        presenter.start(List.of());
        presenter.selectionChanged(Side.LEFT, List.of(entry(Side.LEFT, "a.txt"), entry(Side.LEFT, "b.txt")),
                entry(Side.LEFT, "b.txt"));
        assertTrue(view.status.get(Side.LEFT).startsWith("2 of 3 selected, 3 B"), view.status.get(Side.LEFT));
        view.answers.add(other.toString());

        presenter.copy();

        assertEquals("Copy 2 items to:", view.questions.getLast());
        assertEquals("bb", Files.readString(other.resolve("b.txt")));
        assertTrue(Files.exists(home.resolve("a.txt")));
        assertEquals(List.of("..", "a.txt", "b.txt"), names(Side.RIGHT));
        assertEquals("Copied 2 files to " + other + ".", view.message);
        assertTrue(view.progressClosed);
    }

    @Test
    void parentLineIsNeverATarget() {
        presenter.start(List.of());
        cursor(Side.LEFT, "..");
        presenter.copy();
        assertEquals("Nothing selected.", view.message);
        assertTrue(view.questions.isEmpty());
    }

    @Test
    void conflictAnswerForAllIsAskedOnce() throws IOException {
        Files.writeString(other.resolve("a.txt"), "old a");
        Files.writeString(other.resolve("b.txt"), "old b");
        presenter.start(List.of());
        presenter.selectionChanged(Side.LEFT, List.of(entry(Side.LEFT, "a.txt"), entry(Side.LEFT, "b.txt")), null);
        view.answers.add(other.toString());
        view.conflicts.add(new ConflictChoice(Resolution.OVERWRITE, true));

        presenter.move();

        assertEquals(1, view.askedConflicts);
        assertEquals("a", Files.readString(other.resolve("a.txt")));
        assertEquals("bb", Files.readString(other.resolve("b.txt")));
        assertFalse(Files.exists(home.resolve("a.txt")));
    }

    @Test
    void cancelledTargetQuestionDoesNothing() {
        presenter.start(List.of());
        cursor(Side.LEFT, "a.txt");
        presenter.move(); // no answer queued: the question is cancelled
        assertTrue(Files.exists(home.resolve("a.txt")));
        assertFalse(view.progressShown);
    }

    @Test
    void trashesWithoutAskingAndDeletesPermanentlyOnlyAfterConfirmation() {
        presenter.start(List.of());
        cursor(Side.LEFT, "a.txt");
        presenter.moveToTrash();
        assertFalse(Files.exists(home.resolve("a.txt")));
        assertTrue(Files.exists(trash.homeTrashFiles().resolve("a.txt")));
        assertEquals("Moved 1 item to the trash.", view.message);
        assertEquals(List.of("..", "Docs", "b.txt"), names(Side.LEFT));

        cursor(Side.LEFT, "b.txt");
        view.confirmations.add(false);
        presenter.deletePermanently();
        assertTrue(Files.exists(home.resolve("b.txt")));
        view.confirmations.add(true);
        presenter.deletePermanently();
        assertFalse(Files.exists(home.resolve("b.txt")));
    }

    @Test
    void theTrashShowsOriginsAndRestores() throws IOException {
        presenter.start(List.of());
        cursor(Side.LEFT, "a.txt");
        presenter.moveToTrash();
        presenter.selectionChanged(Side.LEFT, List.of(), entry(Side.LEFT, "Docs"));
        presenter.moveToTrash();

        presenter.goTrash();
        assertEquals(trash.homeTrashFiles(), view.dirs.get(Side.LEFT));
        assertEquals(Boolean.TRUE, view.trashViews.get(Side.LEFT));
        assertEquals(List.of("Docs", "a.txt"), names(Side.LEFT), "no parent line in the trash");
        assertEquals(home, entry(Side.LEFT, "a.txt").trash().originalFolder());
        assertNotNull(entry(Side.LEFT, "a.txt").trash().deletedAt());
        cursor(Side.LEFT, "a.txt");
        assertEquals("a.txt, 1 B, from " + home, view.status.get(Side.LEFT).split(" · ")[0]);

        presenter.restore();
        assertEquals("a", Files.readString(home.resolve("a.txt")));
        assertEquals("Restored " + home.resolve("a.txt") + ".", view.message);
        assertEquals(List.of("Docs"), names(Side.LEFT));
        assertFalse(Files.exists(trash.homeTrashFiles().resolveSibling("info/a.txt.trashinfo")));

        // Restoring onto an existing file is refused.
        Files.createDirectory(home.resolve("Docs"));
        cursor(Side.LEFT, "Docs");
        presenter.restore();
        assertTrue(view.error.contains("already exists"), view.error);
        assertTrue(Files.exists(trash.homeTrashFiles().resolve("Docs")));

        // Leaving the trash upwards lands in the home folder, not in the trash's own folders.
        presenter.up(Side.LEFT);
        assertEquals(home, view.dirs.get(Side.LEFT));
        assertEquals(Boolean.FALSE, view.trashViews.get(Side.LEFT));
    }

    @Test
    void inTheTrashF8DeletesForGoodAndEmptyTrashClearsEverything() throws IOException {
        presenter.start(List.of());
        cursor(Side.LEFT, "a.txt");
        presenter.moveToTrash();
        cursor(Side.LEFT, "b.txt");
        presenter.moveToTrash();
        presenter.goTrash();

        cursor(Side.LEFT, "a.txt");
        view.confirmations.add(false);
        presenter.moveToTrash();
        assertTrue(Files.exists(trash.homeTrashFiles().resolve("a.txt")));
        view.confirmations.add(true);
        presenter.moveToTrash();
        assertFalse(Files.exists(trash.homeTrashFiles().resolve("a.txt")));
        assertFalse(Files.exists(trash.homeTrashFiles().resolveSibling("info/a.txt.trashinfo")), "info removed too");

        view.confirmations.add(true);
        presenter.emptyTrash();
        assertEquals(List.of(), names(Side.LEFT));
        assertEquals("The trash is empty.", view.status.get(Side.LEFT).split(" · ")[0]);
        assertEquals(0, Files.list(trash.homeTrashFiles().resolveSibling("info")).count());
        assertEquals("Emptied the trash: deleted 1 file.", view.message);

        presenter.emptyTrash();
        assertEquals("The trash is empty.", view.message);
    }

    @Test
    void trashArgumentOpensTheTrashAsTheFrontPanelDoes() {
        presenter.start(List.of("trash:"));
        assertEquals(trash.homeTrashFiles(), view.dirs.get(Side.LEFT));
        assertEquals(Boolean.TRUE, view.trashViews.get(Side.LEFT));
        assertTrue(Files.isDirectory(trash.homeTrashFiles()), "created on first use");
        assertEquals(other, view.dirs.get(Side.RIGHT));
    }

    @Test
    void newFolderAndRenamePutTheCursorOnTheResult() {
        presenter.start(List.of());
        view.answers.add("Projects");
        presenter.newFolder();
        assertTrue(Files.isDirectory(home.resolve("Projects")));
        assertEquals("Projects", view.focus.get(Side.LEFT));

        cursor(Side.LEFT, "a.txt");
        view.answers.add("c.txt");
        presenter.rename();
        assertTrue(Files.exists(home.resolve("c.txt")));
        assertEquals("c.txt", view.focus.get(Side.LEFT));
    }

    @Test
    void errorsAreShown() {
        presenter.start(List.of());
        cursor(Side.LEFT, "a.txt");
        view.answers.add("b.txt");
        presenter.rename();
        assertEquals("b.txt already exists.", view.error);
    }

    @Test
    void opensFilesButAsksBeforeRunningPrograms() throws IOException {
        Files.writeString(home.resolve("setup.desktop"), "[Desktop Entry]");
        presenter.start(List.of());
        presenter.open(Side.LEFT, entry(Side.LEFT, "a.txt"));
        verify(opener).open(home.resolve("a.txt"));

        view.confirmations.add(false);
        presenter.open(Side.LEFT, entry(Side.LEFT, "setup.desktop"));
        verify(opener, never()).open(home.resolve("setup.desktop"));
        assertTrue(MainPresenter.canRunProgram(entry(Side.LEFT, "setup.desktop")));
        assertFalse(MainPresenter.canRunProgram(entry(Side.LEFT, "a.txt")));

        cursor(Side.LEFT, "b.txt");
        presenter.edit();
        verify(opener).edit(home.resolve("b.txt"));
    }

    @Test
    void panelCommands() {
        presenter.start(List.of());
        presenter.swapPanels();
        assertEquals(other, view.dirs.get(Side.LEFT));
        assertEquals(home, view.dirs.get(Side.RIGHT));
        presenter.sameFolderInOtherPanel();
        assertEquals(other, view.dirs.get(Side.RIGHT));
        presenter.panelActivated(Side.RIGHT);
        assertEquals(Side.RIGHT, view.active);
        presenter.goHome();
        assertEquals(home, view.dirs.get(Side.RIGHT));
        view.answers.add("Docs");
        presenter.goTo();
        assertEquals(home.resolve("Docs"), view.dirs.get(Side.RIGHT));
    }

    @Test
    void typedPaths() {
        assertEquals(home, presenter.toPath("~", null));
        assertEquals(home.resolve("x"), presenter.toPath("~/x", null));
        assertEquals(other.resolve("y"), presenter.toPath("../other/y", home));
        assertEquals(Path.of("/tmp"), presenter.toPath(" /tmp ", home));
        assertNull(presenter.toPath("  ", home));
    }

    @Test
    void exitSavesTheFolders() {
        presenter.start(List.of());
        presenter.navigate(Side.RIGHT, home.resolve("Docs"));
        presenter.exit();
        verify(preferences).save(FilePreferences.DEFAULTS.withDirs(home, home.resolve("Docs")));
        assertTrue(view.disposed);
    }

    // ---- test doubles ---------------------------------------------------------------------------

    private static final class FakeView implements MainView {
        final Map<Side, Path> dirs = new EnumMap<>(Side.class);
        final Map<Side, List<FileEntry>> entries = new EnumMap<>(Side.class);
        final Map<Side, String> focus = new EnumMap<>(Side.class);
        final Map<Side, String> status = new EnumMap<>(Side.class);
        final Map<Side, Boolean> trashViews = new EnumMap<>(Side.class);
        final Deque<String> answers = new ArrayDeque<>();
        final Deque<Boolean> confirmations = new ArrayDeque<>();
        final Deque<ConflictChoice> conflicts = new ArrayDeque<>();
        final List<String> questions = new ArrayList<>();
        Side active;
        boolean twoPanels;
        String message;
        String error;
        int askedConflicts;
        boolean progressShown;
        boolean progressClosed;
        boolean disposed;

        @Override
        public void showDirectory(Side side, Path dir, List<FileEntry> list, SortOrder sort, String focusName) {
            dirs.put(side, dir);
            entries.put(side, list);
            focus.put(side, focusName);
        }

        @Override
        public void setPanelStatus(Side side, String text) {
            status.put(side, text);
        }

        @Override
        public void setTrashView(Side side, boolean trash) {
            trashViews.put(side, trash);
        }

        @Override
        public void setActive(Side side) {
            active = side;
        }

        @Override
        public void setTwoPanels(boolean value) {
            twoPanels = value;
        }

        @Override
        public void setShowHidden(boolean showHidden) {
        }

        @Override
        public void setStatus(String text) {
            message = text;
        }

        @Override
        public void showError(String text) {
            error = text;
        }

        @Override
        public boolean confirm(String title, String text) {
            return confirmations.isEmpty() || confirmations.removeFirst();
        }

        @Override
        public String askText(String title, String text, String initial) {
            questions.add(text);
            return answers.isEmpty() ? null : answers.removeFirst();
        }

        @Override
        public ConflictChoice askConflict(FileEntry source, FileEntry target) {
            askedConflicts++;
            return conflicts.isEmpty() ? ConflictChoice.CANCEL : conflicts.removeFirst();
        }

        @Override
        public ProgressView showProgress(String title, Runnable cancel) {
            progressShown = true;
            return new ProgressView() {
                @Override
                public void update(Progress progress) {
                }

                @Override
                public void close() {
                    progressClosed = true;
                }
            };
        }

        @Override
        public void copyToClipboard(String text) {
        }

        @Override
        public void dispose() {
            disposed = true;
        }
    }

    private static final class DirectExecutorService extends AbstractExecutorService {
        private boolean shutdown;

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
