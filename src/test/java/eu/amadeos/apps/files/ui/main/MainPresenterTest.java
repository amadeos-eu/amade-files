package eu.amadeos.apps.files.ui.main;

import eu.amadeos.commons.config.XdgDirectories;
import eu.amadeos.commons.desktop.PasswordStore;
import eu.amadeos.commons.desktop.SessionPasswordStore;
import eu.amadeos.apps.files.domain.ClipboardFiles;
import eu.amadeos.apps.files.domain.Credentials;
import eu.amadeos.apps.files.domain.DiscoveredServer;
import eu.amadeos.apps.files.domain.Drive;
import eu.amadeos.apps.files.domain.DropMode;
import eu.amadeos.apps.files.domain.FileEntry;
import eu.amadeos.apps.files.domain.FilePreferences;
import eu.amadeos.apps.files.domain.NetworkAddress;
import eu.amadeos.apps.files.domain.NetworkMount;
import eu.amadeos.apps.files.domain.Place;
import eu.amadeos.apps.files.domain.Progress;
import eu.amadeos.apps.files.domain.Resolution;
import eu.amadeos.apps.files.domain.SavedLocation;
import eu.amadeos.apps.files.domain.Side;
import eu.amadeos.apps.files.domain.SortKey;
import eu.amadeos.apps.files.domain.SortOrder;
import eu.amadeos.apps.files.domain.ViewMode;
import eu.amadeos.apps.files.repository.NetworkRepository;
import eu.amadeos.apps.files.repository.PreferencesRepository;
import eu.amadeos.apps.files.service.ArchiveService;
import eu.amadeos.apps.files.service.DriveService;
import eu.amadeos.apps.files.service.FileOperations;
import eu.amadeos.apps.files.service.FileSystemService;
import eu.amadeos.apps.files.service.NetworkAutostart;
import eu.amadeos.apps.files.service.NetworkLoginException;
import eu.amadeos.apps.files.service.NetworkService;
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
import java.util.HashMap;
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
    private static final FilePreferences PANELS = FilePreferences.DEFAULTS.withViewMode(ViewMode.PANELS);
    private final FakeView view = new FakeView();
    private final PreferencesRepository preferences = mock(PreferencesRepository.class);
    private final Opener opener = mock(Opener.class);
    private final DriveService drives = mock(DriveService.class);
    private final NetworkService network = mock(NetworkService.class);
    private final NetworkRepository networkRepository = mock(NetworkRepository.class);
    private final PasswordStore passwords = new SessionPasswordStore();
    private final NetworkAutostart autostart = mock(NetworkAutostart.class);
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
        // The two-panel view, unless a test switches; the tree view tests start from DEFAULTS.
        when(preferences.load()).thenReturn(PANELS.withDirs(home, other));
        trash = new TrashService(new XdgDirectories(
                Map.of("XDG_DATA_HOME", dir.resolve("data").toString()), home.toString()));
        when(network.mounts()).thenReturn(List.of());
        when(network.discover()).thenReturn(List.of());
        when(networkRepository.load()).thenReturn(NetworkRepository.Data.EMPTY);
        presenter = new MainPresenter(view, new FileSystemService(), new FileOperations(), trash,
                new ArchiveService(), drives, opener, preferences, network, networkRepository, passwords, autostart,
                new DirectExecutorService(), Runnable::run, home);
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

    // --8<-- [start:conflict-all]
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
    // --8<-- [end:conflict-all]

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
        presenter.goTo(Side.RIGHT, "Docs");
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
        verify(preferences).save(PANELS.withDirs(home, home.resolve("Docs")));
        assertTrue(view.disposed);
    }

    // ---- path bar -------------------------------------------------------------------------------

    @Test
    void typedPathsGoToFoldersAndToTheFolderOfAFile() {
        presenter.start(List.of());
        presenter.goTo(Side.LEFT, "~/Docs");
        assertEquals(home.resolve("Docs"), view.dirs.get(Side.LEFT));
        presenter.goTo(Side.LEFT, "../b.txt");
        assertEquals(home, view.dirs.get(Side.LEFT));
        assertEquals("b.txt", view.focus.get(Side.LEFT));
        assertEquals("Docs/", presenter.completePath(Side.LEFT, "Do"));

        presenter.copyPath(home.resolve("Docs"));
        assertEquals(home.resolve("Docs").toString(), view.clipboardText);
    }

    // ---- tree view ------------------------------------------------------------------------------

    private void startTreeView() {
        when(preferences.load()).thenReturn(FilePreferences.DEFAULTS.withDirs(home, other));
        presenter.start(List.of());
    }

    @Test
    void theTreeViewIsTheDefaultAndShowsNoParentLine() {
        startTreeView();
        assertEquals(ViewMode.TREE, view.viewMode);
        assertEquals(List.of("Docs", "a.txt", "b.txt"), names(Side.LEFT));
        assertEquals(home, view.places.get(0).path());
        assertEquals(Place.Kind.TRASH, view.places.get(1).kind());

        presenter.expandFolder(home);
        assertEquals(List.of(home.resolve("Docs")), view.folders.get(home));

        presenter.setViewMode(ViewMode.PANELS);
        assertEquals(ViewMode.PANELS, view.viewMode);
        assertEquals(List.of("..", "Docs", "a.txt", "b.txt"), names(Side.LEFT));
        presenter.exit();
        verify(preferences).save(PANELS.withDirs(home, other));
    }

    @Test
    void theTrashInTheTreeOpensTheTrashEvenBeforeItExists() {
        startTreeView();
        Path files = view.places.get(1).path();
        assertFalse(Files.exists(files), "a fresh user has no trash yet");

        presenter.folderSelected(files);
        assertEquals(trash.homeTrashFiles(), view.dirs.get(Side.LEFT));
        assertEquals(Boolean.TRUE, view.trashViews.get(Side.LEFT));
        assertTrue(Files.isDirectory(trash.homeTrashFiles().resolveSibling("info")), "created on first use");
        assertEquals(List.of(), names(Side.LEFT));
    }

    @Test
    void droppingOnTheTrashTrashesEvenBeforeItExists() {
        startTreeView();
        Path files = view.places.get(1).path();
        assertEquals("Move to Trash", presenter.describeDrop(List.of(home.resolve("a.txt")), files, DropMode.AUTO));
        presenter.drop(List.of(home.resolve("a.txt")), files, DropMode.AUTO);
        assertTrue(Files.exists(trash.homeTrashFiles().resolve("a.txt")));
        assertFalse(Files.exists(home.resolve("a.txt")));
    }

    @Test
    void commandsWorkOnTheFolderChosenInTheTreeWhileTheTreeHasTheFocus() {
        startTreeView();
        presenter.folderSelected(home.resolve("Docs"));
        assertEquals(home.resolve("Docs"), view.dirs.get(Side.LEFT));

        view.answers.add("Papers");
        presenter.rename();
        assertEquals("New name for Docs:", view.questions.getLast());
        assertTrue(Files.isDirectory(home.resolve("Papers")));
        assertEquals(home.resolve("Papers"), view.dirs.get(Side.LEFT), "the contents side follows the new name");

        presenter.moveToTrash();
        assertFalse(Files.exists(home.resolve("Papers")));
        assertEquals(home, view.dirs.get(Side.LEFT), "the nearest existing folder is shown");

        // Back in the contents, the commands work on its lines again.
        presenter.panelActivated(Side.LEFT);
        cursor(Side.LEFT, "a.txt");
        presenter.moveToTrash();
        assertFalse(Files.exists(home.resolve("a.txt")));
    }

    @Test
    void placesStayWhereTheyAre() {
        startTreeView();
        presenter.folderSelected(home);
        presenter.moveToTrash();
        presenter.rename();
        assertTrue(Files.isDirectory(home));
        assertEquals("Home cannot be moved, renamed or deleted.", view.message);
        assertNull(presenter.describeDrop(List.of(home), other, DropMode.MOVE));
    }

    @Test
    void openInTwoPanelsSwitchesTheView() {
        startTreeView();
        presenter.openInPanels(home.resolve("Docs"));
        assertEquals(ViewMode.PANELS, view.viewMode);
        assertEquals(home.resolve("Docs"), view.dirs.get(Side.LEFT));
        assertEquals("..", names(Side.LEFT).getFirst());
    }

    // ---- clipboard and drag and drop ------------------------------------------------------------

    @Test
    void cutAndPasteMovesOnceCopyAndPasteCopies() throws IOException {
        presenter.start(List.of());
        cursor(Side.LEFT, "a.txt");
        presenter.cutFiles();
        assertEquals(new ClipboardFiles(List.of(home.resolve("a.txt")), true), view.clipboard);
        presenter.panelActivated(Side.RIGHT);
        presenter.paste();
        assertTrue(Files.exists(other.resolve("a.txt")));
        assertFalse(Files.exists(home.resolve("a.txt")));
        assertEquals(List.of(), view.clipboard.paths(), "cut files are pasted once");

        presenter.panelActivated(Side.LEFT);
        cursor(Side.LEFT, "b.txt");
        presenter.copyFiles();
        presenter.pasteInto(home.resolve("Docs"));
        assertEquals("bb", Files.readString(home.resolve("Docs/b.txt")));
        presenter.paste();
        assertTrue(Files.exists(home.resolve("b (2).txt")), "pasting into the same folder makes a copy beside it");
        assertTrue(Files.exists(home.resolve("b.txt")));

        view.clipboard = null;
        presenter.paste();
        assertEquals("There are no files on the clipboard.", view.message);
    }

    // --8<-- [start:drop-test]
    @Test
    void droppingMovesOnTheSameFileSystemCopiesWithCtrlAndTrashesOnTheTrash() {
        presenter.start(List.of());
        Path a = home.resolve("a.txt");
        Path b = home.resolve("b.txt");
        Path docs = home.resolve("Docs");
        assertEquals("Move to \"Docs\"", presenter.describeDrop(List.of(a), docs, DropMode.AUTO));
        assertEquals("Copy to \"Docs\"", presenter.describeDrop(List.of(a), docs, DropMode.COPY));
        assertNull(presenter.describeDrop(List.of(a), home, DropMode.AUTO), "back where it is");
        assertNull(presenter.describeDrop(List.of(docs), docs.resolve("x"), DropMode.COPY), "into itself");
        assertEquals("Move to Trash", presenter.describeDrop(List.of(a), trash.ensureHomeTrash(), DropMode.AUTO));

        presenter.drop(List.of(a), docs, DropMode.AUTO);
        assertTrue(Files.exists(docs.resolve("a.txt")));
        assertFalse(Files.exists(a));

        presenter.drop(List.of(b), other, DropMode.COPY);
        assertTrue(Files.exists(other.resolve("b.txt")));
        assertTrue(Files.exists(b));

        presenter.drop(List.of(b), trash.homeTrashFiles(), DropMode.AUTO);
        assertFalse(Files.exists(b));
        assertTrue(Files.exists(trash.homeTrashFiles().resolve("b.txt")));
    }
    // --8<-- [end:drop-test]

    // ---- test doubles ---------------------------------------------------------------------------

    // ---- archives and drives -------------------------------------------------------------------

    @Test
    void compressesTheMarkedFilesAndExtractsByOpening() throws IOException {
        presenter.start(List.of());
        presenter.selectionChanged(Side.LEFT, List.of(entry(Side.LEFT, "a.txt"), entry(Side.LEFT, "Docs")),
                entry(Side.LEFT, "Docs"));
        view.archiveChoices.add(new ArchiveChoice("bundle", ArchiveService.Format.ZIP));
        presenter.compress();
        assertTrue(view.questions.getLast().endsWith("[home]"), view.questions.getLast());
        assertTrue(Files.isRegularFile(home.resolve("bundle.zip")), view.error);
        assertEquals("bundle.zip", view.focus.get(Side.LEFT));
        assertTrue(view.message.startsWith("Created bundle.zip with 1 file"), view.message);

        cursor(Side.LEFT, "bundle.zip");
        presenter.open(Side.LEFT, entry(Side.LEFT, "bundle.zip"));
        assertTrue(Files.isRegularFile(home.resolve("bundle/a.txt")), view.error);
        assertTrue(Files.isDirectory(home.resolve("bundle/Docs")));
        assertEquals("bundle", view.focus.get(Side.LEFT));
        verifyNoInteractions(opener);
    }

    @Test
    void extractHereNeedsAnArchive() {
        presenter.start(List.of());
        cursor(Side.LEFT, "a.txt");
        presenter.extractHere();
        assertEquals("Select a zip, tar or 7z archive to extract.", view.message);
    }

    private static final Drive STICK = new Drive("/dev/sdb1", "/dev/sdb", "STICK", "", "vfat", 8_000_000_000L, null, false);

    @Test
    void choosingADriveMountsAndOpensIt() throws IOException {
        Path mount = Files.createDirectories(dir.resolve("media/STICK"));
        Files.writeString(mount.resolve("photo.jpg"), "jpg");
        when(preferences.load()).thenReturn(FilePreferences.DEFAULTS.withDirs(home, other));
        when(drives.drives()).thenReturn(List.of(STICK));
        when(drives.mount(STICK)).thenAnswer(i -> {
            when(drives.drives()).thenReturn(List.of(STICK.mountedAt(mount)));
            return mount;
        });
        presenter.start(List.of());
        Place place = lastDrive();
        assertTrue(place.needsMount());
        assertEquals("STICK", place.name());
        // A stick that was there before Files started is not mounted on its own.
        presenter.refreshPlaces();
        verify(drives, never()).mount(any());

        presenter.folderSelected(place.path());
        verify(drives).mount(STICK);
        assertEquals(mount, view.dirs.get(Side.LEFT));
        assertEquals(List.of("photo.jpg"), names(Side.LEFT));
        assertEquals(mount, lastDrive().path());
        assertFalse(lastDrive().needsMount());
    }

    @Test
    void aStickPluggedInLaterIsMountedAtOnce() {
        when(drives.drives()).thenReturn(List.of());
        presenter.start(List.of());
        when(drives.drives()).thenReturn(List.of(STICK));
        when(drives.mount(STICK)).thenReturn(dir);
        presenter.refreshPlaces();
        verify(drives).mount(STICK);
    }

    @Test
    void safelyRemoveLeavesTheDriveFirstAndIsNotUndoneByThePoll() throws IOException {
        Path mount = Files.createDirectories(dir.resolve("media/STICK"));
        Drive mounted = STICK.mountedAt(mount);
        when(drives.drives()).thenReturn(List.of(mounted));
        presenter.start(List.of(mount.toString()));
        assertEquals(mount, view.dirs.get(Side.LEFT));
        doAnswer(i -> {
            when(drives.drives()).thenReturn(List.of(STICK));
            return null;
        }).when(drives).eject(mounted);

        presenter.eject(lastDrive());
        assertEquals(home, view.dirs.get(Side.LEFT));
        assertEquals("STICK can be removed now.", view.message);
        assertTrue(lastDrive().needsMount());
        presenter.refreshPlaces();
        verify(drives, never()).mount(any());
    }

    private Place lastDrive() {
        return view.places.stream().filter(p -> p.kind() == Place.Kind.MEDIA).reduce((a, b) -> b).orElseThrow();
    }

    // ---- network ------------------------------------------------------------------------------------------------

    private static final NetworkAddress NAS = NetworkAddress.parse("smb://mycloudex2ultra.local");

    private Place networkPlace(Path parent, String name) {
        return view.networkPlaces.get(parent).stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void networkListsTheServersAndTheirSharesAndConnectsAGuestShare() throws IOException {
        Path folder = Files.createDirectories(dir.resolve("gvfs/smb-share:server=mycloudex2ultra.local,share=public"));
        Files.writeString(folder.resolve("photo.jpg"), "jpg");
        when(preferences.load()).thenReturn(FilePreferences.DEFAULTS.withDirs(home, other));
        when(network.discover()).thenReturn(List.of(new DiscoveredServer("MyCloudEX2Ultra", NAS)));
        when(network.shares(any(), any())).thenReturn(List.of("Public", "ralf"));
        presenter.start(List.of());
        assertEquals(Place.Kind.NETWORK, view.places.getLast().kind());

        Place server = networkPlace(Place.NETWORK_PATH, "MyCloudEX2Ultra");
        presenter.expandFolder(server.path());
        Place share = networkPlace(server.path(), "Public");
        assertTrue(share.needsConnect());

        NetworkAddress publicShare = NAS.withPath("Public");
        when(network.connect(publicShare, null)).thenAnswer(i -> {
            when(network.mounts()).thenReturn(List.of(new NetworkMount(publicShare, folder)));
            return folder;
        });
        presenter.folderSelected(share.path());
        assertEquals(folder, view.dirs.get(Side.LEFT));
        assertEquals(List.of("photo.jpg"), names(Side.LEFT));
        assertEquals(folder, networkPlace(server.path(), "Public").path());
        assertEquals("MyCloudEX2Ultra › Public", view.folderNames.get(folder));
    }

    @Test
    void aShareThatWantsAPasswordAsksAndRemembersTheLogin() throws IOException {
        Path folder = Files.createDirectories(dir.resolve("gvfs/ralf"));
        when(preferences.load()).thenReturn(FilePreferences.DEFAULTS.withDirs(home, other));
        when(network.discover()).thenReturn(List.of(new DiscoveredServer("MyCloudEX2Ultra", NAS)));
        when(network.shares(any(), any())).thenReturn(List.of("ralf"));
        NetworkAddress share = NAS.withPath("ralf");
        Credentials login = Credentials.of("ralf", "WORKGROUP", "secret12");
        when(network.connect(share, null)).thenThrow(new NetworkLoginException("the share ralf wants a password.", false));
        when(network.connect(share, Credentials.of("ralf", "WORKGROUP", "wrong")))
                .thenThrow(new NetworkLoginException("The password was not accepted.", true));
        when(network.connect(share, login)).thenReturn(folder);
        view.logins.add(new LoginChoice(Credentials.of("ralf", "WORKGROUP", "wrong"), true, true));
        view.logins.add(new LoginChoice(login, true, true));
        presenter.start(List.of());
        Place server = networkPlace(Place.NETWORK_PATH, "MyCloudEX2Ultra");
        presenter.expandFolder(server.path());

        presenter.folderSelected(networkPlace(server.path(), "ralf").path());
        assertEquals(2, view.loginRequests.size());
        assertTrue(view.loginRequests.getLast().message().contains("not accepted"));
        assertEquals(folder, view.dirs.get(Side.LEFT));
        assertEquals("secret12", passwords.lookup("smb://WORKGROUP;ralf@mycloudex2ultra.local/ralf").orElseThrow());
        verify(networkRepository).save(argThat(d -> d.locations().size() == 1 && d.locations().getFirst().autoConnect()
                && d.locations().getFirst().address().user().equals("ralf")));
        verify(autostart).update(true);
    }

    @Test
    void aSavedLoginConnectsWithThePasswordFromTheKeyring() throws IOException {
        Path folder = Files.createDirectories(dir.resolve("gvfs/ralf"));
        NetworkAddress saved = NetworkAddress.parse("smb://WORKGROUP;ralf@mycloudex2ultra.local/ralf");
        when(networkRepository.load()).thenReturn(new NetworkRepository.Data(
                List.of(new SavedLocation("", saved, false, true, false)), List.of()));
        passwords.store(saved.uri(), "", "secret12");
        when(preferences.load()).thenReturn(FilePreferences.DEFAULTS.withDirs(home, other));
        when(network.connect(saved, Credentials.of("ralf", "WORKGROUP", "secret12"))).thenReturn(folder);
        presenter.start(List.of());
        Place server = networkPlace(Place.NETWORK_PATH, "mycloudex2ultra");
        presenter.folderSelected(networkPlace(server.path(), "ralf").path());
        assertTrue(view.loginRequests.isEmpty());
        assertEquals(folder, view.dirs.get(Side.LEFT));
    }

    @Test
    void anAddressTypedIntoThePathBarConnects() throws IOException {
        Path folder = Files.createDirectories(dir.resolve("gvfs/public/Photos"));
        when(preferences.load()).thenReturn(FilePreferences.DEFAULTS.withDirs(home, other));
        NetworkAddress photos = NetworkAddress.parse("smb://nas/Public/Photos");
        when(network.connect(photos, null)).thenReturn(folder);
        presenter.start(List.of());
        presenter.goTo(Side.LEFT, "smb://nas/Public/Photos");
        assertEquals(folder, view.dirs.get(Side.LEFT));
        verify(networkRepository).save(argThat(d -> d.recent().equals(List.of(NetworkAddress.parse("smb://nas/Public/Photos")))));
    }

    private static final class FakeView implements MainView {
        final Map<Side, Path> dirs = new EnumMap<>(Side.class);
        final Map<Side, List<FileEntry>> entries = new EnumMap<>(Side.class);
        final Map<Side, String> focus = new EnumMap<>(Side.class);
        final Map<Side, String> status = new EnumMap<>(Side.class);
        final Map<Side, Boolean> trashViews = new EnumMap<>(Side.class);
        final Deque<String> answers = new ArrayDeque<>();
        final Deque<Boolean> confirmations = new ArrayDeque<>();
        final Deque<ConflictChoice> conflicts = new ArrayDeque<>();
        final Deque<ArchiveChoice> archiveChoices = new ArrayDeque<>();
        final List<String> questions = new ArrayList<>();
        final Map<Path, List<Path>> folders = new HashMap<>();
        List<Place> places = List.of();
        ClipboardFiles clipboard;
        String clipboardText;
        ViewMode viewMode;
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
        public ArchiveChoice askArchive(String text, String initialName) {
            questions.add(text + " [" + initialName + "]");
            return archiveChoices.isEmpty() ? null : archiveChoices.removeFirst();
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
            clipboardText = text;
        }

        @Override
        public void putFilesOnClipboard(ClipboardFiles files) {
            clipboard = files;
        }

        @Override
        public ClipboardFiles filesOnClipboard() {
            return clipboard;
        }

        @Override
        public void setViewMode(ViewMode mode) {
            viewMode = mode;
        }

        @Override
        public void setPlaces(List<Place> list) {
            places = list;
        }

        @Override
        public void showFolders(Path dir, List<Path> list) {
            folders.put(dir, list);
        }

        final Map<Path, List<Place>> networkPlaces = new HashMap<>();
        Map<Path, String> folderNames = Map.of();
        final Deque<LoginChoice> logins = new ArrayDeque<>();
        final List<LoginRequest> loginRequests = new ArrayList<>();
        ConnectChoice connectChoice;

        @Override
        public void showPlaces(Path parent, List<Place> list, boolean complete) {
            networkPlaces.put(parent, List.copyOf(list));
        }

        @Override
        public void setFolderNames(Map<Path, String> names) {
            folderNames = names;
        }

        @Override
        public LoginChoice askLogin(LoginRequest request) {
            loginRequests.add(request);
            return logins.poll();
        }

        @Override
        public ConnectChoice askConnect(List<NetworkAddress> recent, NetworkAddress initial) {
            return connectChoice;
        }

        @Override
        public void refreshFolders() {
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
