package eu.amadeos.apps.files.ui.main;

/**
 * What the log-in dialog shows.
 *
 * @param title        "Log In to MyCloudEX2Ultra"
 * @param message      why it asks, or why the last try failed
 * @param user         filled in
 * @param domain       filled in; the field is only shown for SMB
 * @param smb          shows the workgroup or domain
 * @param guest        Guest is chosen
 * @param remember     "Remember password in the keyring" is ticked
 * @param autoConnect  "Connect again at login" is ticked; null hides it (servers are not connected at login)
 * @param keyring      a keyring is there; without one the password lives as long as Files runs
 */
public record LoginRequest(String title, String message, String user, String domain, boolean smb, boolean guest,
                           boolean remember, Boolean autoConnect, boolean keyring) {
}
