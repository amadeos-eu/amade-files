# amade-files

**amade-files** is the File Manager of the [amadeOS](https://github.com/datazuul/amadeos) desktop: a
two-panel file manager in Java Swing with the look of the CDE desktop and the keyboard of the
orthodox file managers (Midnight Commander, Total Commander, gnome-commander).

![License](https://img.shields.io/badge/license-EUPL--1.2-blue)
![Java](https://img.shields.io/badge/Java-21-orange)
![Debian](https://img.shields.io/badge/Debian-package-red)

> Status: early development (0.1.0). Browsing, copy, move, rename, new folder, trash and permanent
> delete work; see *Roadmap*.

---

## Window layout

```
┌ File Manager - /home/ralf/Dokumente ──────────────────────────────────┐
│ File  Edit  View  Go                                             Help │
├────────────────────────────────┬──────────────────────────────────────┤
│ [/] [home] [ralf] [Dokumente]  │ [/] [media] [USB-STICK]              │  ← iconic path
├────────────────────────────────┼──────────────────────────────────────┤
│ Name ▲         Size   Modified │ Name ▲         Size   Modified       │
│ ..                             │ ..                                   │
│ ▸ Projekte     <DIR>  13.10.   │ ▸ Backup       <DIR>  01.10.         │
│   brief.odt    24 KB  12.10.   │   foto.jpg     2.1 MB 30.09.         │
├────────────────────────────────┼──────────────────────────────────────┤
│ 3 of 12 selected, 2.4 MB · 18 GB free │ 5 items · 3.2 GB free         │
├───────────────────────────────────────────────────────────────────────┤
│ F2 Rename F3 View F4 Edit F5 Copy F6 Move F7 New Folder F8 Trash F10 Quit │
└───────────────────────────────────────────────────────────────────────┘
```

- **Two panels** side by side; *View → Two Panels* (Ctrl+F1) switches to a single panel. The active
  panel has the active title colours in its path bar.
- **Iconic path** as in the CDE File Manager (dtfile): one button per folder; long paths collapse
  into "…" so the current folder stays visible.
- **Marking is separate from the cursor**: Insert or Space marks the line and moves down, arrow keys
  keep the marks. Marked lines are bold and red. Ctrl+click and Shift+click mark with the mouse.
- **Status line per panel**: the file under the cursor or the marked files with their size, and the
  free space of the file system.
- **Function-key bar** and the usual keys:

| Key | Action |
|---|---|
| Enter, double-click | Open folder, or open file with its default application |
| Backspace | Parent folder |
| Tab | Other panel |
| Insert, Space | Mark line and move down |
| F2, Shift+F6 | Rename |
| F3 / F4 | View (default application) / edit in the Text Editor (amade-pad) |
| F5 / F6 | Copy / move to the other panel (the target can be changed) |
| F7 | New folder |
| F8, Delete | Move to trash |
| Shift+F8, Shift+Delete | Delete permanently (asks first) |
| Ctrl+H | Show hidden files |
| Ctrl+L | Go to folder (`~` and relative paths work) |
| Ctrl+U / Ctrl+= | Swap panels / same folder in the other panel |
| Ctrl+A, Ctrl+Shift+A, Ctrl+I | Select all, deselect all, invert selection |
| Ctrl+Shift+C | Copy paths to the clipboard |
| Ctrl+R | Refresh |
| F10, Ctrl+Q | Quit |

## File operations

- **Copy and move run in the background** with a progress dialog (shown after 0.4 s) and Cancel.
- **Copies are safe**: each file is written to a hidden temporary file next to the target and renamed
  into place when complete. A cancelled or failed copy never leaves a half-written file under the
  real name, and an overwritten file is replaced in one step. Modification time and permissions are
  kept.
- **Conflicts**: folders that already exist are merged; for files the dialog shows both versions and
  offers *Overwrite*, *Overwrite All*, *Skip*, *Skip All*, *Keep Both* (`report (2).odt`) and *Cancel*.
- **Moving** within one file system is a rename; across file systems each file is copied and its
  source deleted only after the copy is complete. Skipped files stay in the source.
- **Symbolic links** are copied and deleted as links, never followed.
- Copying a folder into itself is refused; copying into the same folder makes a numbered copy.
- Devices, pipes and sockets are skipped.

## Trash

*Move to Trash* follows the [freedesktop.org Trash specification](https://specifications.freedesktop.org/trash-spec/latest/),
so other desktops and `gio trash --list` see the files and can restore them:

- Files on the home file system go to `~/.local/share/Trash` (`$XDG_DATA_HOME/Trash`).
- Files on other file systems (USB sticks, other partitions) go to `$topdir/.Trash/$uid` if the
  administrator prepared a sticky `.Trash`, otherwise to `$topdir/.Trash-$uid`. They are not copied
  to the home trash, so trashing stays fast and frees the space on that device when the trash is
  emptied.
- If a file system has no usable trash (e.g. read-only), the File Manager offers to delete
  permanently.

*Go → Trash* shows the home trash; restoring and emptying from the File Manager are on the roadmap.

## Security

- Files that can run a program (executable files, `.desktop`, `.sh`, `.jar`, …) are not opened
  without asking.
- File names are shown as they are; nothing is executed through a shell.

## Configuration

`~/.config/amadeos/files/files.conf` (or `$XDG_CONFIG_HOME/amadeos/files/`), readable only by the user:
hidden files, one or two panels, and the folders of both panels when the File Manager was closed.

## Architecture

Java 21, Swing, Guice; same structure as [amade-mail](https://github.com/datazuul/amade-mail) and
[amade-pad](https://github.com/datazuul/amade-pad). Theme, Motif look, start-up and XDG helpers come
from [amade-commons](https://github.com/datazuul/amade-commons), so the File Manager follows the desktop
theme chosen in the Style Manager immediately.

| Package | Content |
|---|---|
| `domain` | `FileEntry`, `SortOrder`, `SortKey`, `Side`, `FilePreferences`, `Resolution`, `Progress` |
| `repository` | `PreferencesRepository` (`files.conf`) |
| `service` | `FileSystemService` (list, new folder, rename), `FileOperations` (copy, move, delete with progress and conflicts), `TrashService` (freedesktop trash), `Opener` (default application, Text Editor), `Formats` |
| `ui.main` | `MainWindow`, `MainMenuBar`, `MainPresenter` (all behaviour, testable without Swing windows) |
| `ui.panel`, `ui.dialogs` | file panel with iconic path, conflict and progress dialogs |

Folders are read and operations run on virtual threads; results come back to the EDT. A listing that
arrives after the user moved on is dropped. Only one copy, move or delete runs at a time.

## Building and testing

Requirements: Java 21 JDK; `dpkg-deb` for the Debian package.

`amade-commons` is taken from JitPack. If it is checked out next to this repository
(`../amade-commons`), Gradle builds that checkout instead, so changes to both can be tested together.

```bash
./gradlew test                 # unit tests, no display needed
./gradlew run                  # start the File Manager
./gradlew run --args="/tmp /media"   # folders for the left and right panel
./gradlew deb                  # build/distributions/amade-files_0.1.0_all.deb
./gradlew installToAmadeOs     # copy the .deb into ../amadeos/config/packages.chroot/
```

The package installs `amade-files.desktop` (`Categories=System;FileManager;`, `MimeType=inode/directory;`),
so the amadeOS front panel lists it in the File Manager drawer.

## Roadmap

- [x] Two panels, iconic path, marks, function-key bar, sorting, hidden files
- [x] Copy, move, rename, new folder with progress, cancel and conflict dialog
- [x] Freedesktop trash (home and top-directory trash), permanent delete
- [ ] Trash view with restore and empty; Front Panel Trash Can integration
- [ ] Watch folders for changes (refresh automatically)
- [ ] Quick search by typing; filter (`*.jpg`)
- [ ] Tabs per panel; bookmarks; history (Alt+Left/Right)
- [ ] Icon view and folder tree; file type icons from the icon theme (NsCDE)
- [ ] Drag and drop between panels, onto the desktop and the Front Panel
- [ ] Properties (permissions, owner), Open With…, find files, compare folders
- [ ] Archives as folders; remote folders (SFTP)

## License

amade-files is free software, licensed under the
[European Union Public Licence v. 1.2](LICENSE) (EUPL-1.2, SPDX identifier `EUPL-1.2`), like amadeOS.

Copyright © 2026 datazuul.com and contributors.

Third-party libraries keep their licences: Guice (Apache-2.0), SLF4J (MIT), Logback (EPL-1.0 / LGPL-2.1).
