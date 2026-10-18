package eu.amadeos.files.service;

/**
 * The file system of a file has no usable trash, e.g. a read-only medium. The user may delete the
 * file permanently instead.
 */
public final class TrashUnavailableException extends FileOperationException {
    public TrashUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
