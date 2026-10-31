package eu.amadeos.apps.files.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NetworkAddressTest {

    @Test
    void readsAllParts() {
        NetworkAddress a = NetworkAddress.parse("smb://WORKGROUP;ralf@MyCloudEX2Ultra.local:445/ralf/My%20Documents/");
        assertEquals("smb", a.scheme());
        assertEquals("WORKGROUP", a.domain());
        assertEquals("ralf", a.user());
        assertEquals("MyCloudEX2Ultra.local", a.host());
        assertEquals(0, a.port(), "the default port is left out");
        assertEquals("ralf/My Documents", a.path());
        assertEquals("ralf", a.share());
        assertEquals("smb://WORKGROUP;ralf@MyCloudEX2Ultra.local/ralf/My%20Documents", a.uri());
        assertEquals("smb://MyCloudEX2Ultra.local/ralf/My%20Documents", a.plainUri());
        assertEquals("MyCloudEX2Ultra", a.serverName());
    }

    @Test
    void withoutSchemeItIsSmbAndWindowsPathsWork() {
        assertEquals("smb://nas/Public", NetworkAddress.parse("nas/Public").uri());
        assertEquals("smb://nas/Public/Photos", NetworkAddress.parse("\\\\nas\\Public\\Photos").uri());
        assertEquals("sftp://ralf@office-pc:2222/home/ralf", NetworkAddress.parse("sftp://ralf@office-pc:2222/home/ralf").uri());
    }

    @Test
    void refusesWhatGioCannotConnect() {
        assertThrows(IllegalArgumentException.class, () -> NetworkAddress.parse("http://example.org"));
        assertThrows(IllegalArgumentException.class, () -> NetworkAddress.parse("smb:///"));
    }

    @Test
    void theKeyIgnoresUserCaseOfTheShareAndDotLocal() {
        assertEquals(NetworkAddress.parse("smb://ralf@MyCloud.local/Public").key(),
                NetworkAddress.parse("smb://mycloud/public").key());
        assertNotEquals(NetworkAddress.parse("smb://mycloud/Public").key(), NetworkAddress.parse("smb://mycloud/ralf").key());
        assertEquals(NetworkAddress.parse("smb://mycloud/Public").key(), NetworkAddress.parse("smb://mycloud/Public/Photos").shareAddress().key());
    }

    @Test
    void encodesNonAsciiAndSpaces() {
        NetworkAddress a = new NetworkAddress("smb", "", "", "nas", 0, "Fotos/Über uns");
        assertEquals("smb://nas/Fotos/%C3%9Cber%20uns", a.uri());
        assertEquals(a, NetworkAddress.parse(a.uri()));
    }
}
