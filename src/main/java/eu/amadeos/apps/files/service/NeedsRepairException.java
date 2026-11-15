package eu.amadeos.apps.files.service;

import eu.amadeos.apps.files.domain.Drive;

/**
 * A drive cannot be mounted because its file system needs a check first, usually because the drive was
 * pulled out or lost its connection while it was mounted. {@link DriveService#repair} may cure it.
 */
public class NeedsRepairException extends FileOperationException {
    private final transient Drive drive;

    public NeedsRepairException(Drive drive, String message) {
        super(message);
        this.drive = drive;
    }

    public Drive drive() {
        return drive;
    }
}
