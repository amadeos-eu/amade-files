package eu.amadeos.apps.files.service;

/**
 * A file operation failed. The message is meant for the status line and error dialogs.
 */
public class FileOperationException extends RuntimeException {
    public FileOperationException(String message) {
        super(message);
    }

    public FileOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
