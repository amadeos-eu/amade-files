package eu.amadeos.apps.files.ui.main;

import eu.amadeos.apps.files.domain.Credentials;

/**
 * The answer of the log-in dialog.
 *
 * @param credentials guest, or user, domain and password
 * @param remember    keep the password in the login keyring
 * @param autoConnect connect again at login
 */
public record LoginChoice(Credentials credentials, boolean remember, boolean autoConnect) {
}
