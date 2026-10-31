package eu.amadeos.apps.files.service;

/**
 * A server or share wants a user name and password, or did not accept the ones given.
 */
public class NetworkLoginException extends FileOperationException {
    private final boolean rejected;

    public NetworkLoginException(String message, boolean rejected) {
        super(message);
        this.rejected = rejected;
    }

    /** True if a password was given and refused; false if none was given yet. */
    public boolean rejected() {
        return rejected;
    }
}
