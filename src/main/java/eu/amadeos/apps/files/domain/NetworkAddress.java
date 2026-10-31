package eu.amadeos.apps.files.domain;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/**
 * Where a network server or share lives, as gvfs addresses it:
 * {@code scheme://[domain;][user@]host[:port]/path}. The parts are kept decoded; {@link #uri()} encodes them
 * again. No password is ever part of an address: it goes to {@code gio} on its standard input.
 *
 * @param scheme {@code smb}, {@code nfs}, {@code dav}, {@code davs}, {@code ftp}, {@code sftp} (or {@code afp})
 * @param domain the Windows workgroup or domain, SMB only; empty if not given
 * @param user   the user name; empty for guests and before one is known
 * @param host   the server's name or address
 * @param port   0 for the default port of the scheme
 * @param path   below the server, without leading or trailing slash; for SMB the share comes first
 */
public record NetworkAddress(String scheme, String domain, String user, String host, int port, String path) {

    public NetworkAddress {
        scheme = Objects.requireNonNull(scheme, "scheme").toLowerCase(Locale.ROOT);
        domain = domain == null ? "" : domain.strip();
        user = user == null ? "" : user.strip();
        host = Objects.requireNonNull(host, "host").strip();
        path = trimSlashes(path == null ? "" : path);
        if (port == defaultPort(scheme)) port = 0;
    }

    /** The schemes Files connects to, with the name the user sees. */
    public enum Type {
        SMB("smb", "Windows share (SMB)"),
        NFS("nfs", "NFS"),
        DAV("dav", "WebDAV"),
        DAVS("davs", "WebDAV (secure)"),
        FTP("ftp", "FTP"),
        SFTP("sftp", "SFTP (SSH)");

        private final String scheme;
        private final String label;

        Type(String scheme, String label) {
            this.scheme = scheme;
            this.label = label;
        }

        public String scheme() {
            return scheme;
        }

        @Override
        public String toString() {
            return label;
        }

        public static Type of(String scheme) {
            for (Type type : values()) if (type.scheme.equalsIgnoreCase(scheme)) return type;
            return null;
        }
    }

    // --8<-- [start:parse]
    /**
     * Reads a typed or found address. A missing scheme means SMB, so {@code mycloudex2ultra/Public} works, and
     * Windows' own {@code \\server\share} too.
     *
     * @throws IllegalArgumentException if it has no server or a scheme Files cannot connect to
     */
    public static NetworkAddress parse(String text) {
        String value = Objects.requireNonNull(text, "text").strip();
        if (value.startsWith("\\\\")) value = "smb://" + value.substring(2).replace('\\', '/');
        int colon = value.indexOf("://");
        String scheme = colon > 0 ? value.substring(0, colon) : "smb";
        String rest = colon > 0 ? value.substring(colon + 3) : value;
        if (Type.of(scheme) == null && !scheme.equalsIgnoreCase("afp")) {
            throw new IllegalArgumentException("Files cannot connect to " + scheme + ":// addresses.");
        }
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        String path = slash < 0 ? "" : rest.substring(slash + 1);
        String domain = "";
        String user = "";
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            user = decode(authority.substring(0, at));
            authority = authority.substring(at + 1);
            int semicolon = user.indexOf(';');
            if (semicolon >= 0) {
                domain = user.substring(0, semicolon);
                user = user.substring(semicolon + 1);
            }
        }
        int port = 0;
        int portColon = authority.lastIndexOf(':');
        // [fe80::1]:445 or host:445, but not a bare IPv6 address
        if (portColon > 0 && authority.indexOf(':') == portColon || authority.startsWith("[") && authority.contains("]:")) {
            try {
                port = Integer.parseInt(authority.substring(portColon + 1));
                authority = authority.substring(0, portColon);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("\"" + text + "\" has no valid port.");
            }
        }
        if (authority.isBlank()) throw new IllegalArgumentException("\"" + text + "\" names no server.");
        return new NetworkAddress(scheme, domain, user, decode(authority), port, decode(path));
    }
    // --8<-- [end:parse]

    /** The full address with user and domain, as {@code gio} takes it. */
    public String uri() {
        StringBuilder b = new StringBuilder(scheme).append("://");
        if (!user.isEmpty()) {
            if (!domain.isEmpty()) b.append(encode(domain, false)).append(';');
            b.append(encode(user, false)).append('@');
        }
        b.append(host);
        if (port != 0) b.append(':').append(port);
        b.append('/');
        if (!path.isEmpty()) b.append(encode(path, true));
        return b.toString();
    }

    /** The address without user and domain, for showing and copying. */
    public String plainUri() {
        return withLogin("", "").uri();
    }

    public NetworkAddress withLogin(String newDomain, String newUser) {
        return new NetworkAddress(scheme, newDomain, newUser, host, port, path);
    }

    public NetworkAddress withPath(String newPath) {
        return new NetworkAddress(scheme, domain, user, host, port, newPath);
    }

    /** The server alone: the same address without a path. */
    public NetworkAddress server() {
        return withPath("");
    }

    public boolean isSmb() {
        return scheme.equals("smb");
    }

    /** The SMB share, the first part of the path; empty for the server itself. */
    public String share() {
        int slash = path.indexOf('/');
        return slash < 0 ? path : path.substring(0, slash);
    }

    /** The SMB share as its own address (the server for other schemes, which have no shares). */
    public NetworkAddress shareAddress() {
        return isSmb() ? withPath(share()) : this;
    }

    /**
     * Two addresses of the same place, whatever the user and the spelling of the host: {@code MyCloud},
     * {@code mycloud.local} and {@code mycloud.local.} are one server; for SMB the share's case does not count.
     */
    public String key() {
        String family = scheme.equals("davs") ? "dav" : scheme;
        String p = isSmb() ? share().toLowerCase(Locale.ROOT) : path;
        return family + "://" + hostKey(host) + (port != 0 ? ":" + port : "") + "/" + p;
    }

    /** The host without {@code .local} and in lower case. */
    public static String hostKey(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.endsWith(".local")) h = h.substring(0, h.length() - ".local".length());
        return h;
    }

    /** The server's name for people: the host without {@code .local}. */
    public String serverName() {
        String h = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        return h.toLowerCase(Locale.ROOT).endsWith(".local") ? h.substring(0, h.length() - ".local".length()) : h;
    }

    public static int defaultPort(String scheme) {
        return switch (scheme.toLowerCase(Locale.ROOT)) {
            case "smb" -> 445;
            case "sftp" -> 22;
            case "ftp" -> 21;
            case "dav" -> 80;
            case "davs" -> 443;
            case "nfs" -> 2049;
            case "afp" -> 548;
            default -> -1;
        };
    }

    @Override
    public String toString() {
        return plainUri();
    }

    private static String trimSlashes(String p) {
        int start = 0;
        int end = p.length();
        while (start < end && p.charAt(start) == '/') start++;
        while (end > start && p.charAt(end - 1) == '/') end--;
        return p.substring(start, end);
    }

    /** Percent-encodes what may not stand in an address; {@code /} stays in paths. */
    static String encode(String text, boolean path) {
        StringBuilder b = new StringBuilder();
        for (byte raw : text.getBytes(StandardCharsets.UTF_8)) {
            int c = raw & 0xFF;
            boolean plain = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || "-._~!$&'()*+,=".indexOf(c) >= 0 || path && (c == '/' || c == ':' || c == '@' || c == ';');
            if (plain) b.append((char) c);
            else b.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                    .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
        }
        return b.toString();
    }

    /** Undoes {@code %xx}; a lone {@code %} stays as it is. */
    public static String decode(String text) {
        if (text.indexOf('%') < 0) return text;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '%' && i + 2 < bytes.length) {
                int hi = Character.digit(bytes[i + 1], 16);
                int lo = Character.digit(bytes[i + 2], 16);
                if (hi >= 0 && lo >= 0) {
                    out.write(hi << 4 | lo);
                    i += 2;
                    continue;
                }
            }
            out.write(bytes[i]);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
