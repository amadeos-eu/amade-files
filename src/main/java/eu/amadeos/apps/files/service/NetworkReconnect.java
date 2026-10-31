package eu.amadeos.apps.files.service;

import eu.amadeos.apps.files.domain.Credentials;
import eu.amadeos.apps.files.domain.SavedLocation;
import eu.amadeos.apps.files.repository.NetworkRepository;
import eu.amadeos.commons.desktop.PasswordStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code amade-files --connect-saved}, run once at login: connects the shares marked "Connect again at login"
 * with the passwords from the keyring, without a window. A share that is not reachable or wants a password
 * that is not stored is left for later; Files asks when it is chosen.
 */
public final class NetworkReconnect {
    private static final Logger LOG = LoggerFactory.getLogger(NetworkReconnect.class);

    private final NetworkService network;
    private final NetworkRepository repository;
    private final PasswordStore passwords;

    public NetworkReconnect(NetworkService network, NetworkRepository repository, PasswordStore passwords) {
        this.network = network;
        this.repository = repository;
        this.passwords = passwords;
    }

    /** Returns how many could not be connected. */
    public int run() {
        int failed = 0;
        for (SavedLocation location : repository.load().locations()) {
            if (!location.autoConnect()) continue;
            Credentials credentials;
            if (location.guest() || location.address().user().isEmpty()) {
                credentials = Credentials.GUEST;
            } else {
                String password = passwords.lookup(location.address().uri()).orElse(null);
                if (password == null) {
                    LOG.info("No password stored for {}; not connected", location.address());
                    failed++;
                    continue;
                }
                credentials = Credentials.of(location.address().user(), location.address().domain(), password);
            }
            try {
                network.connect(location.address(), credentials);
                LOG.info("Connected {}", location.address());
            } catch (FileOperationException e) {
                LOG.warn("Cannot connect {}: {}", location.address(), e.getMessage());
                failed++;
            }
        }
        return failed;
    }
}
