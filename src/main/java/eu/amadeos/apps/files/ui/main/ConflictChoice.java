package eu.amadeos.apps.files.ui.main;

import eu.amadeos.apps.files.domain.Resolution;

/**
 * The user's answer to a conflict: a {@link Resolution}, optionally for all further conflicts of the
 * operation.
 */
public record ConflictChoice(Resolution resolution, boolean all) {
    public static final ConflictChoice CANCEL = new ConflictChoice(Resolution.CANCEL, false);
}
