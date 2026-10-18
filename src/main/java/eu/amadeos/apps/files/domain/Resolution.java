package eu.amadeos.apps.files.domain;

/**
 * What to do when the target of a copy or move already exists.
 */
public enum Resolution {
    /** Replace the existing file. */
    OVERWRITE,
    /** Keep the existing file and leave the source alone. */
    SKIP,
    /** Copy under a new name, e.g. {@code report (2).odt}. */
    KEEP_BOTH,
    /** Stop the whole operation. */
    CANCEL
}
