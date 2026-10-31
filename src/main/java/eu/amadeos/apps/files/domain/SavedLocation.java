package eu.amadeos.apps.files.domain;

import java.util.Objects;

/**
 * A server or share the user keeps under Network, or logged in to before. Never holds the password, which
 * lives in the login keyring.
 *
 * @param name        shown in the tree; empty for the address's own name
 * @param address     with user and domain if the user logs in
 * @param guest       connects without a password
 * @param keep        stays in the tree even when the server is not found
 * @param autoConnect connected again at login
 */
public record SavedLocation(String name, NetworkAddress address, boolean guest, boolean keep, boolean autoConnect) {

    public SavedLocation {
        name = name == null ? "" : name.strip();
        Objects.requireNonNull(address, "address");
    }

    public SavedLocation withAddress(NetworkAddress newAddress) {
        return new SavedLocation(name, newAddress, guest, keep, autoConnect);
    }
}
