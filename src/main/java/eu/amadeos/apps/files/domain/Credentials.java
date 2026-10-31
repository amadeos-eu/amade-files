package eu.amadeos.apps.files.domain;

/**
 * How to log in to a server or share.
 *
 * @param guest    without a password (anonymous); user, domain and password are ignored then
 * @param user     the user name
 * @param domain   the Windows workgroup or domain (SMB); empty for the other schemes
 * @param password never written to disk by Files; to the keyring only if the user asks
 */
public record Credentials(boolean guest, String user, String domain, String password) {

    public static final Credentials GUEST = new Credentials(true, "", "", "");

    public Credentials {
        user = user == null ? "" : user.strip();
        domain = domain == null ? "" : domain.strip();
        password = password == null ? "" : password;
    }

    public static Credentials of(String user, String domain, String password) {
        return new Credentials(false, user, domain, password);
    }

    @Override
    public String toString() {
        // The password stays out of logs.
        return guest ? "Credentials[guest]" : "Credentials[" + (domain.isEmpty() ? "" : domain + ";") + user + "]";
    }
}
