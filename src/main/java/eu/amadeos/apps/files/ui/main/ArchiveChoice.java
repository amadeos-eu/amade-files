package eu.amadeos.apps.files.ui.main;

import eu.amadeos.apps.files.service.ArchiveService;

/**
 * The answer of the Compress dialog.
 *
 * @param name   the archive's name without the ending
 * @param format zip, tar.gz or tar.xz
 */
public record ArchiveChoice(String name, ArchiveService.Format format) {
}
