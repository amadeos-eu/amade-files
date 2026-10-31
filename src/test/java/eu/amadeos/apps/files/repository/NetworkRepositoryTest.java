package eu.amadeos.apps.files.repository;

import eu.amadeos.apps.files.domain.NetworkAddress;
import eu.amadeos.apps.files.domain.SavedLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NetworkRepositoryTest {
    @TempDir
    Path dir;

    @Test
    void keepsLocationsAndRecentAddressesButNoPasswords() throws Exception {
        NetworkRepository repository = new NetworkRepository(dir.resolve("network.conf"));
        assertEquals(NetworkRepository.Data.EMPTY, repository.load());
        NetworkRepository.Data data = NetworkRepository.Data.EMPTY
                .with(new SavedLocation("", NetworkAddress.parse("smb://WORKGROUP;ralf@nas/ralf"), false, true, true))
                .withRecent(NetworkAddress.parse("smb://ralf@nas/Public"))
                .withRecent(NetworkAddress.parse("nfs://nas/nfs/Backup"))
                .withRecent(NetworkAddress.parse("smb://nas/public"));
        repository.save(data);
        NetworkRepository.Data read = repository.load();
        assertEquals(data, read);
        assertEquals(List.of("smb://nas/public", "nfs://nas/nfs/Backup"), read.recent().stream().map(NetworkAddress::uri).toList());
        assertTrue(read.anyAutoConnect());
        assertFalse(Files.readString(dir.resolve("network.conf")).contains("password="));
        assertNotNull(read.find(NetworkAddress.parse("smb://nas.local/RALF")));
    }
}
