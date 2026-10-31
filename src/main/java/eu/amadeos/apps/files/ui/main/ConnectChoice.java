package eu.amadeos.apps.files.ui.main;

import eu.amadeos.apps.files.domain.NetworkAddress;

/**
 * The answer of Connect to Server.
 *
 * @param address the server, share or folder
 * @param keep    keep it in the folder tree under Network
 */
public record ConnectChoice(NetworkAddress address, boolean keep) {
}
