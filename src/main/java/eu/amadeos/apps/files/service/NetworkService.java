package eu.amadeos.apps.files.service;

import com.google.inject.Inject;
import eu.amadeos.apps.files.domain.Credentials;
import eu.amadeos.apps.files.domain.DiscoveredServer;
import eu.amadeos.apps.files.domain.NetworkAddress;
import eu.amadeos.apps.files.domain.NetworkMount;
import eu.amadeos.commons.process.CommandRunner;
import eu.amadeos.commons.process.ProgramMissingException;
import eu.amadeos.commons.process.ProgramResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Network servers and shares through gvfs and its {@code gio} command (packages gvfs, gvfs-backends,
 * gvfs-fuse): SMB, NFS, WebDAV, FTP and SFTP. Everything runs as the user, without root or fstab. A connected
 * share is an ordinary folder below {@code $XDG_RUNTIME_DIR/gvfs}, so the file operations, drag and drop and
 * the other programs work on it unchanged.
 *
 * <p>Servers are found in {@code network:///}, where gvfs merges what mDNS (avahi) and WS-Discovery (wsdd)
 * announce. Passwords go to {@code gio mount} on its standard input when it asks for them, never on its
 * command line; user and workgroup are part of the address, so gio asks for the password only.
 */
public class NetworkService {
    private static final Logger LOG = LoggerFactory.getLogger(NetworkService.class);

    /** English messages and prompts, whatever the user's language, so they can be read. */
    static final List<String> GIO = List.of("env", "LC_ALL=C", "gio");
    static final String ATTRIBUTES = "standard::display-name,standard::target-uri";
    private static final Duration LIST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration MOUNT_TIMEOUT = Duration.ofSeconds(60);
    /** Schemes Files shows when gvfs finds them on the network. */
    private static final Set<String> FOUND_SCHEMES = Set.of("smb", "sftp", "ftp", "dav", "davs", "nfs");

    private final CommandRunner runner;
    private final Path gvfsRoot;

    @Inject
    public NetworkService() {
        this(CommandRunner.system(), defaultGvfsRoot());
    }

    NetworkService(CommandRunner runner, Path gvfsRoot) {
        this.runner = runner;
        this.gvfsRoot = gvfsRoot;
    }

    /** {@code $XDG_RUNTIME_DIR/gvfs}, where gvfs-fuse shows what is connected. */
    static Path defaultGvfsRoot() {
        String runtime = System.getenv("XDG_RUNTIME_DIR");
        if (runtime != null && !runtime.isBlank()) return Path.of(runtime, "gvfs");
        try {
            Object uid = Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:uid");
            return Path.of("/run/user", String.valueOf(uid), "gvfs");
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            return Path.of("/run/user/1000/gvfs");
        }
    }

    public Path gvfsRoot() {
        return gvfsRoot;
    }

    // ---- connected ------------------------------------------------------------------------------

    // --8<-- [start:mounts]
    /** What is connected now, read from the folder names below {@link #gvfsRoot}; empty without gvfs-fuse. */
    public List<NetworkMount> mounts() {
        List<NetworkMount> mounts = new ArrayList<>();
        if (!Files.isDirectory(gvfsRoot)) return mounts;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(gvfsRoot)) {
            for (Path entry : entries) {
                NetworkAddress address = parseMountName(entry.getFileName().toString());
                if (address != null) mounts.add(new NetworkMount(address, entry));
            }
        } catch (IOException e) {
            LOG.debug("Cannot read {}", gvfsRoot, e);
        }
        mounts.sort((a, b) -> a.root().compareTo(b.root()));
        return mounts;
    }

    /**
     * The address of a gvfs folder name such as {@code smb-share:domain=WORKGROUP,server=nas,share=ralf,user=ralf}
     * or {@code dav:host=cloud.example.org,ssl=true,user=ralf,prefix=%2Fremote.php%2Fdav}; null for what is no
     * share (the server browser {@code smb-server}, {@code network}, ...).
     */
    static NetworkAddress parseMountName(String name) {
        int colon = name.indexOf(':');
        if (colon <= 0) return null;
        String type = name.substring(0, colon);
        Map<String, String> spec = new HashMap<>();
        for (String pair : name.substring(colon + 1).split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) spec.put(pair.substring(0, eq), NetworkAddress.decode(pair.substring(eq + 1)));
        }
        String host = spec.getOrDefault("server", spec.get("host"));
        if (host == null || host.isBlank()) return null;
        int port = 0;
        try {
            if (spec.containsKey("port")) port = Integer.parseInt(spec.get("port"));
        } catch (NumberFormatException ignored) {
            // the default port then
        }
        String user = spec.getOrDefault("user", "");
        String prefix = spec.getOrDefault("prefix", spec.getOrDefault("volume", ""));
        return switch (type) {
            case "smb-share" -> spec.containsKey("share")
                    ? new NetworkAddress("smb", spec.getOrDefault("domain", ""), user, host, port, spec.get("share")) : null;
            case "dav" -> new NetworkAddress("true".equals(spec.get("ssl")) ? "davs" : "dav", "", user, host, port, prefix);
            case "sftp", "ftp", "ftps", "nfs" -> new NetworkAddress(type.equals("ftps") ? "ftp" : type, "", user, host, port, prefix);
            case "afp-volume" -> new NetworkAddress("afp", "", user, host, port, spec.getOrDefault("volume", ""));
            default -> null;
        };
    }
    // --8<-- [end:mounts]

    // ---- found on the network -------------------------------------------------------------------

    // --8<-- [start:discover]
    /** The servers announced on the network; empty if gvfs finds none or is missing. */
    public List<DiscoveredServer> discover() {
        try {
            String output = list("network:///");
            return parseNetwork(output);
        } catch (IOException e) {
            LOG.debug("Cannot look for servers: {}", e.getMessage());
            return List.of();
        }
    }

    /** The lines of {@code gio list -a ... network:///}: one server per scheme and host. */
    static List<DiscoveredServer> parseNetwork(String output) {
        Map<String, DiscoveredServer> servers = new LinkedHashMap<>();
        for (Map<String, String> entry : parseList(output)) {
            String target = entry.get("standard::target-uri");
            if (target == null) continue;
            NetworkAddress address;
            try {
                address = NetworkAddress.parse(target).server();
            } catch (IllegalArgumentException e) {
                continue; // smb:/// (the old Windows network browser) and schemes Files does not handle
            }
            if (!FOUND_SCHEMES.contains(address.scheme())) continue;
            String name = entry.getOrDefault("standard::display-name", address.serverName());
            servers.putIfAbsent(address.key(), new DiscoveredServer(name, address));
        }
        return List.copyOf(servers.values());
    }
    // --8<-- [end:discover]

    /**
     * The entries of {@code gio list -a ATTRIBUTES}: name, then the attributes, as in
     * {@code Public	0	(mountable)	standard::display-name=Public standard::target-uri=smb://nas/Public}.
     */
    static List<Map<String, String>> parseList(String output) {
        List<Map<String, String>> entries = new ArrayList<>();
        for (String line : output.split("\n")) {
            String[] columns = line.split("\t", 4);
            if (columns.length < 1 || columns[0].isBlank()) continue;
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("name", columns[0]);
            if (columns.length == 4) {
                for (String attribute : columns[3].split(" (?=[a-z-]+::[a-z-]+=)")) {
                    int eq = attribute.indexOf('=');
                    if (eq > 0) entry.put(attribute.substring(0, eq), attribute.substring(eq + 1).strip());
                }
            }
            entries.add(entry);
        }
        return entries;
    }

    // ---- shares of a server ---------------------------------------------------------------------

    // --8<-- [start:shares]
    /**
     * The shares of an SMB server, sorted by name; administrative shares ({@code C$}, {@code IPC$}) are left out.
     * Without credentials the server is asked as a guest.
     *
     * @throws NetworkLoginException  if the server shows its shares to registered users only
     * @throws FileOperationException if it cannot be reached
     */
    public List<String> shares(NetworkAddress server, Credentials credentials) {
        NetworkAddress browse = login(server.server(), credentials);
        mount(browse, credentials);
        try {
            List<String> shares = new ArrayList<>();
            for (Map<String, String> entry : parseList(list(browse.uri()))) {
                String name = entry.get("name");
                if (name != null && !name.endsWith("$")) shares.add(NetworkAddress.decode(name));
            }
            shares.sort(String.CASE_INSENSITIVE_ORDER);
            return shares;
        } catch (IOException e) {
            throw new FileOperationException(message(e.getMessage(), server));
        }
    }
    // --8<-- [end:shares]

    // ---- connect and disconnect -----------------------------------------------------------------

    /**
     * Connects a share or location and returns its folder.
     *
     * @param credentials null tries as a guest
     * @throws NetworkLoginException  if a password is needed or was refused
     * @throws FileOperationException if the server is not reachable, the share unknown, or gvfs-fuse missing
     */
    public Path connect(NetworkAddress location, Credentials credentials) {
        NetworkAddress target = login(location, credentials);
        mount(target.shareAddress(), credentials);
        Path folder = NetworkPlaces.folderOf(target, mounts());
        if (folder == null) {
            throw new FileOperationException(location.serverName() + " is connected, but there is no folder for it in "
                    + gvfsRoot + ". Is the package gvfs-fuse installed?");
        }
        return folder;
    }

    /** Disconnects what {@code mount} shows. */
    public void disconnect(NetworkMount mount) {
        ProgramResult result = run(List.of("mount", "-u", mount.address().uri()), null, LIST_TIMEOUT);
        if (!result.succeeded()) {
            throw new FileOperationException(message(result.stderrText(), mount.address()));
        }
    }

    /** The address with the user and domain of the credentials (none for guests). */
    public static NetworkAddress login(NetworkAddress address, Credentials credentials) {
        if (credentials == null || credentials.guest()) return address.withLogin("", "");
        String domain = address.isSmb() ? (credentials.domain().isEmpty() ? "WORKGROUP" : credentials.domain()) : "";
        return address.withLogin(domain, credentials.user());
    }

    // --8<-- [start:mount]
    /**
     * {@code gio mount} with a short dialogue on its standard input: it asks for the password (and, when no user is
     * in the address, the user) the way it would ask in a terminal. A second request for the password means the
     * first was wrong. Guests and calls without credentials mount anonymously ({@code -a}).
     */
    void mount(NetworkAddress address, Credentials credentials) {
        boolean anonymous = credentials == null || credentials.guest();
        List<String> args = anonymous ? List.of("mount", "-a", address.uri()) : List.of("mount", address.uri());
        Dialogue dialogue = new Dialogue(credentials);
        ProgramResult result = run(args, dialogue, MOUNT_TIMEOUT);
        if (result.succeeded()) return;
        String error = result.stderrText();
        if (error.contains("already mounted")) return;
        if (dialogue.refused || anonymous && isLoginNeeded(error)) {
            throw new NetworkLoginException(dialogue.refused && !anonymous
                    ? "The user name or password for " + describe(address) + " was not accepted."
                    : capitalize(describe(address)) + " wants a user name and password.", dialogue.refused && !anonymous);
        }
        if (!anonymous && isRefused(error)) {
            throw new NetworkLoginException("The user name or password for " + describe(address) + " was not accepted.", true);
        }
        if (dialogue.question != null) {
            throw new FileOperationException(address.serverName() + " asks: " + dialogue.question);
        }
        throw new FileOperationException(message(error, address));
    }
    // --8<-- [end:mount]

    static boolean isLoginNeeded(String error) {
        String e = error.toLowerCase(Locale.ROOT);
        return e.contains("anonymous access denied") || e.contains("password dialog cancelled")
                || e.contains("permission denied") || e.contains("access denied") || e.contains("logon failure")
                || e.contains("aborted") || e.contains("cancelled");
    }

    static boolean isRefused(String error) {
        String e = error.toLowerCase(Locale.ROOT);
        return e.contains("permission denied") || e.contains("access denied") || e.contains("logon failure")
                || e.contains("login incorrect") || e.contains("authentication failed");
    }

    /** "the share ralf on MyCloudEX2Ultra" or "cloud.example.org". */
    static String describe(NetworkAddress address) {
        return address.isSmb() && !address.share().isEmpty()
                ? "the share " + address.share() + " on " + address.serverName() : address.serverName();
    }

    /** gio's error without its {@code gio: smb://...: } in front, with a hint for the usual causes. */
    static String message(String error, NetworkAddress address) {
        String text = error == null ? "" : error.strip();
        for (String line : text.split("\n")) {
            if (line.startsWith("gio:")) {
                text = line;
                break;
            }
        }
        if (text.startsWith("gio:")) text = text.substring(4).strip();
        int uriEnd = text.indexOf("/: ");
        if (text.contains("://") && uriEnd > 0) text = text.substring(uriEnd + 3);
        else if (text.contains("://") && text.indexOf(": ") > 0) text = text.substring(text.indexOf(": ", text.indexOf("://")) + 2);
        String lower = text.toLowerCase(Locale.ROOT);
        String where = describe(address);
        if (lower.contains("no such file") || lower.contains("not found") && !lower.contains("host")) {
            return capitalize(where) + " does not exist (" + text + ").";
        }
        if (lower.contains("timed out") || lower.contains("connection refused") || lower.contains("no route")
                || lower.contains("unreachable") || lower.contains("failed to retrieve share list")
                || lower.contains("name or service not known") || lower.contains("host not found")
                || lower.contains("invalid argument") && address.isSmb()) {
            return address.serverName() + " cannot be reached (" + text + "). Is it switched on and in this network?";
        }
        if (lower.contains("not supported")) {
            return address.scheme() + ":// is not available. Is the package gvfs-backends installed? (" + text + ")";
        }
        return "Cannot connect to " + where + ": " + (text.isEmpty() ? "gio failed" : text);
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }

    // ---- running gio ----------------------------------------------------------------------------

    /** {@code gio list -a ATTRIBUTES uri}, mounting {@code uri} first if gvfs says it is not mounted. */
    private String list(String uri) throws IOException {
        List<String> args = List.of("list", "-a", ATTRIBUTES, uri);
        ProgramResult result = run(args, null, LIST_TIMEOUT);
        if (!result.succeeded() && result.stderrText().contains("not mounted")) {
            run(List.of("mount", uri), null, LIST_TIMEOUT);
            result = run(args, null, LIST_TIMEOUT);
        }
        if (!result.succeeded()) throw new IOException(result.stderrText().strip());
        return result.stdoutText();
    }

    /** Answers gio's prompts on its standard input. */
    static final class Dialogue {
        private final Credentials credentials;
        boolean passwordGiven;
        boolean userGiven;
        /** gio asked for the password (or the user) a second time, or wanted one that was not given. */
        boolean refused;
        /** A question with choices (such as an unknown SSH host key) that Files does not answer for the user. */
        String question;

        Dialogue(Credentials credentials) {
            this.credentials = credentials;
        }

        /**
         * The answer to a prompt, the text gio printed since the last answer; null ends the dialogue (gio then
         * gets the end of its input and gives up).
         */
        String answer(String output) {
            String prompt = lastLine(output).strip();
            String p = prompt.toLowerCase(Locale.ROOT);
            boolean noLogin = credentials == null || credentials.guest();
            if (p.startsWith("password")) {
                if (noLogin || passwordGiven) {
                    refused = true;
                    return null;
                }
                passwordGiven = true;
                return credentials.password();
            }
            if (p.startsWith("user")) {
                if (noLogin || userGiven) {
                    refused = true;
                    return null;
                }
                userGiven = true;
                return credentials.user();
            }
            if (p.startsWith("domain")) {
                return noLogin ? null : credentials.domain().isEmpty() ? "WORKGROUP" : credentials.domain();
            }
            question = output.strip().replaceAll("\\s+", " ");
            return null;
        }

        private static String lastLine(String text) {
            String t = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
            int nl = t.lastIndexOf('\n');
            return nl < 0 ? t : t.substring(nl + 1);
        }
    }

    /**
     * Runs gio. With a dialogue, its output is watched while it runs: a line that ends in {@code ": "} without a
     * newline is a prompt, answered on standard input. Without one, standard input is closed at once.
     */
    // --8<-- [start:run]
    ProgramResult run(List<String> args, Dialogue dialogue, Duration timeout) {
        List<String> command = new ArrayList<>(GIO);
        command.addAll(args);
        Process process;
        try {
            process = runner.start(command);
        } catch (ProgramMissingException e) {
            throw new FileOperationException("gio is missing. Install the packages gvfs and gvfs-backends.");
        } catch (IOException e) {
            throw new FileOperationException("Cannot start gio: " + e.getMessage());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread outReader = Thread.ofVirtual().start(() -> copy(process.getInputStream(), out));
        Thread errReader = Thread.ofVirtual().start(() -> copy(process.getErrorStream(), err));
        OutputStream in = process.getOutputStream();
        long deadline = System.nanoTime() + timeout.toNanos();
        int answered = 0;
        try {
            if (dialogue == null) in.close();
            while (process.isAlive()) {
                if (System.nanoTime() > deadline) {
                    process.destroyForcibly();
                    throw new FileOperationException("The server did not answer within " + timeout.toSeconds() + " seconds.");
                }
                if (dialogue != null) {
                    String text;
                    synchronized (out) {
                        text = out.toString(StandardCharsets.UTF_8);
                    }
                    if (text.length() > answered && text.endsWith(": ")) {
                        String answer = dialogue.answer(text.substring(answered));
                        answered = text.length();
                        if (answer == null) {
                            in.close();
                            dialogue = null;
                        } else {
                            in.write((answer + "\n").getBytes(StandardCharsets.UTF_8));
                            in.flush();
                        }
                    }
                }
                Thread.sleep(25);
            }
            outReader.join(Duration.ofSeconds(2));
            errReader.join(Duration.ofSeconds(2));
        } catch (IOException e) {
            // gio ended while it was being answered; its exit code tells the rest
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new FileOperationException("Cancelled.");
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
        int exit;
        try {
            exit = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exit = -1;
        }
        synchronized (out) {
            synchronized (err) {
                return new ProgramResult(exit, out.toByteArray(), err.toByteArray());
            }
        }
    }
    // --8<-- [end:run]

    private static void copy(InputStream in, ByteArrayOutputStream out) {
        byte[] buffer = new byte[4096];
        try (in) {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                synchronized (out) {
                    out.write(buffer, 0, n);
                }
            }
        } catch (IOException ignored) {
            // the process ended
        }
    }
}
