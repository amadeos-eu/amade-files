package eu.amadeos.apps.files.service;

import eu.amadeos.apps.files.domain.Credentials;
import eu.amadeos.apps.files.domain.DiscoveredServer;
import eu.amadeos.apps.files.domain.NetworkAddress;
import eu.amadeos.apps.files.domain.NetworkMount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class NetworkServiceTest {
    @TempDir
    Path dir;

    @Test
    void readsTheGvfsFolderNames() {
        assertEquals(NetworkAddress.parse("smb://WORKGROUP;ralf@nas/ralf"),
                NetworkService.parseMountName("smb-share:domain=WORKGROUP,server=nas,share=ralf,user=ralf"));
        assertEquals(NetworkAddress.parse("smb://nas.local/public"),
                NetworkService.parseMountName("smb-share:server=nas.local,share=public"));
        assertEquals(NetworkAddress.parse("davs://ralf@cloud.example.org/remote.php/dav"),
                NetworkService.parseMountName("dav:host=cloud.example.org,ssl=true,user=ralf,prefix=%2Fremote.php%2Fdav"));
        assertEquals(NetworkAddress.parse("sftp://ralf@office-pc.local:2222/"),
                NetworkService.parseMountName("sftp:host=office-pc.local,port=2222,user=ralf"));
        assertNull(NetworkService.parseMountName("smb-server:server=nas"));
        assertNull(NetworkService.parseMountName("network:"));
    }

    @Test
    void mountsComeFromTheGvfsFolder() throws IOException {
        Files.createDirectories(dir.resolve("smb-share:server=nas,share=public"));
        Files.createDirectories(dir.resolve("smb-server:server=nas"));
        NetworkService service = new NetworkService(command -> {
            throw new IOException("not used");
        }, dir);
        List<NetworkMount> mounts = service.mounts();
        assertEquals(1, mounts.size());
        assertEquals(dir.resolve("smb-share:server=nas,share=public").resolve("Photos"),
                mounts.getFirst().folderOf(NetworkAddress.parse("smb://nas.local/Public/Photos")));
        assertEquals("smb://nas/public/Photos",
                mounts.getFirst().addressOf(mounts.getFirst().root().resolve("Photos")).plainUri());
    }

    @Test
    void findsServersOncePerSchemeAndHost() {
        String output = """
                dnssd-server-MyCloudEX2Ultra._smb._tcp	0	(shortcut)	standard::display-name=MyCloudEX2Ultra standard::target-uri=smb://mycloudex2ultra.local:445/
                wsdd-MYCLOUDEX2ULTRA	0	(shortcut)	standard::display-name=MYCLOUDEX2ULTRA standard::target-uri=smb://mycloudex2ultra/
                dnssd-server-office-pc._sftp-ssh._tcp	0	(shortcut)	standard::display-name=Office PC standard::target-uri=sftp://office-pc.local:22/
                windows-network	0	(shortcut)	standard::display-name=Windows Network standard::target-uri=smb:///
                dnssd-printer	0	(shortcut)	standard::display-name=Printer standard::target-uri=ipp://printer.local/
                """;
        List<DiscoveredServer> servers = NetworkService.parseNetwork(output);
        assertEquals(List.of(new DiscoveredServer("MyCloudEX2Ultra", NetworkAddress.parse("smb://mycloudex2ultra.local/")),
                new DiscoveredServer("Office PC", NetworkAddress.parse("sftp://office-pc.local/"))), servers);
    }

    @Test
    void sharesLeaveOutAdministrativeShares() {
        List<List<String>> commands = new ArrayList<>();
        NetworkService service = new NetworkService(command -> {
            commands.add(command);
            if (command.contains("list")) {
                return process("Public\t0\t(mountable)\tstandard::display-name=Public standard::target-uri=smb://nas/Public\n"
                        + "IPC$\t0\t(mountable)\tstandard::display-name=IPC$\n"
                        + "ralf\t0\t(mountable)\tstandard::display-name=ralf\n", "", 0);
            }
            return process("", "", 0);
        }, dir);
        assertEquals(List.of("Public", "ralf"), service.shares(NetworkAddress.parse("smb://nas"), null));
        assertEquals(List.of("env", "LC_ALL=C", "gio", "mount", "-a", "smb://nas/"), commands.getFirst());
    }

    @Test
    void theGuestTryReportsThatALoginIsNeeded() {
        NetworkService service = new NetworkService(command -> process("", "gio: smb://nas/ralf/: Anonymous access denied\n", 2), dir);
        NetworkLoginException e = assertThrows(NetworkLoginException.class,
                () -> service.connect(NetworkAddress.parse("smb://nas/ralf"), null));
        assertFalse(e.rejected());
        assertEquals("The share ralf on nas wants a user name and password.", e.getMessage());
    }

    @Test
    void thePasswordGoesToStandardInputAndASecondPromptMeansItWasWrong() throws IOException {
        Path share = Files.createDirectories(dir.resolve("smb-share:domain=WORKGROUP,server=nas,share=ralf,user=ralf"));
        List<String> mounted = new ArrayList<>();
        NetworkService service = new NetworkService(command -> {
            mounted.add(command.getLast());
            return gio(input -> input.equals("secret12"));
        }, dir);
        Path folder = service.connect(NetworkAddress.parse("smb://nas/ralf"), Credentials.of("ralf", "", "secret12"));
        assertEquals(share, folder);
        assertEquals("smb://WORKGROUP;ralf@nas/ralf", mounted.getFirst(), "user and workgroup in the address");

        NetworkLoginException e = assertThrows(NetworkLoginException.class,
                () -> service.connect(NetworkAddress.parse("smb://nas/ralf"), Credentials.of("ralf", "", "wrong")));
        assertTrue(e.rejected());
    }

    @Test
    void messagesNameTheCause() {
        NetworkAddress nas = NetworkAddress.parse("smb://nas/Public");
        assertEquals("nas cannot be reached (Failed to retrieve share list from server: Connection timed out). "
                        + "Is it switched on and in this network?",
                NetworkService.message("gio: smb://nas/Public/: Failed to retrieve share list from server: Connection timed out", nas));
    }

    // ---- fake gio -----------------------------------------------------------------------------------------------

    private static Process process(String out, String err, int exit) {
        return new FakeProcess(new ByteArrayInputStream(out.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayInputStream(err.getBytes(StandardCharsets.UTF_8)), OutputStream.nullOutputStream(), exit);
    }

    /** Asks "Password: " like gio mount, twice at most, and succeeds if the answer is accepted. */
    private static Process gio(Function<String, Boolean> accepts) throws IOException {
        PipedInputStream stdout = new PipedInputStream();
        PipedOutputStream toStdout = new PipedOutputStream(stdout);
        PipedInputStream stdin = new PipedInputStream();
        PipedOutputStream toStdin = new PipedOutputStream(stdin);
        FakeProcess process = new FakeProcess(stdout, InputStream.nullInputStream(), toStdin, -1);
        Thread.ofVirtual().start(() -> {
            try (toStdout; BufferedReader in = new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8))) {
                for (int attempt = 0; attempt < 2; attempt++) {
                    toStdout.write("\nAuthentication Required\nEnter password for share ralf on nas:\nPassword: "
                            .getBytes(StandardCharsets.UTF_8));
                    toStdout.flush();
                    String line = in.readLine();
                    if (line == null) break;
                    if (accepts.apply(line)) {
                        process.finish(0);
                        return;
                    }
                }
                process.finish(2);
            } catch (IOException e) {
                process.finish(2);
            }
        });
        return process;
    }

    private static final class FakeProcess extends Process {
        private final InputStream out;
        private final InputStream err;
        private final OutputStream in;
        private volatile int exit;

        FakeProcess(InputStream out, InputStream err, OutputStream in, int exit) {
            this.out = out;
            this.err = err;
            this.in = in;
            this.exit = exit;
        }

        void finish(int code) {
            exit = code;
        }

        @Override
        public OutputStream getOutputStream() {
            return in;
        }

        @Override
        public InputStream getInputStream() {
            return out;
        }

        @Override
        public InputStream getErrorStream() {
            return err;
        }

        @Override
        public int waitFor() throws InterruptedException {
            while (exit < 0) Thread.sleep(5);
            return exit;
        }

        @Override
        public boolean isAlive() {
            return exit < 0;
        }

        @Override
        public int exitValue() {
            if (exit < 0) throw new IllegalThreadStateException();
            return exit;
        }

        @Override
        public void destroy() {
            exit = 137;
        }
    }
}
