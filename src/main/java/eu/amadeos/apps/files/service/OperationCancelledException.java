package eu.amadeos.apps.files.service;

/**
 * The user cancelled a running operation. Everything finished before stays done.
 */
public final class OperationCancelledException extends FileOperationException {
    public OperationCancelledException() {
        super("Cancelled.");
    }
}
