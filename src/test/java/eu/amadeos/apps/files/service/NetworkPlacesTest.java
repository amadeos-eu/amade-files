package eu.amadeos.apps.files.service;

import eu.amadeos.apps.files.domain.DiscoveredServer;
import eu.amadeos.apps.files.domain.NetworkAddress;
import eu.amadeos.apps.files.domain.NetworkMount;
import eu.amadeos.apps.files.domain.Place;
import eu.amadeos.apps.files.domain.SavedLocation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NetworkPlacesTest {
    private static final NetworkAddress NAS = NetworkAddress.parse("smb://mycloudex2ultra.local/");
    private static final Path PUBLIC = Path.of("/run/user/1000/gvfs/smb-share:server=mycloudex2ultra.local,share=public");

    @Test
    void serversWithTheirSharesThenOtherLocationsThenConnect() {
        NetworkPlaces.Tree tree = NetworkPlaces.build(
                List.of(new DiscoveredServer("MyCloudEX2Ultra", NAS),
                        new DiscoveredServer("office-pc", NetworkAddress.parse("sftp://office-pc.local/"))),
                List.of(new SavedLocation("", NetworkAddress.parse("smb://WORKGROUP;ralf@mycloudex2ultra/ralf"), false, true, true)),
                List.of(new NetworkMount(NetworkAddress.parse("smb://mycloudex2ultra.local/public"), PUBLIC)),
                Map.of(NAS.key(), List.of("Public", "ralf", "TimeMachineBackup")));

        assertEquals(List.of("MyCloudEX2Ultra", "office-pc (SFTP)", "Connect to Server…"),
                tree.top().stream().map(Place::name).toList());
        Place server = tree.top().getFirst();
        assertEquals(Place.Kind.SERVER, server.kind());
        List<Place> shares = tree.children().get(server.path());
        assertEquals(List.of("Public", "ralf", "TimeMachineBackup"), shares.stream().map(Place::name).toList());
        assertEquals(PUBLIC, shares.get(0).path(), "connected: its folder");
        assertTrue(shares.get(1).needsConnect());
        assertEquals("ralf", shares.get(1).address().user(), "the saved login");
        assertEquals("MyCloudEX2Ultra › Public", tree.folderNames().get(PUBLIC));
        assertSame(shares.get(1), tree.find(shares.get(1).path()));
        assertEquals(PUBLIC, tree.mountOf(PUBLIC.resolve("Photos")).root());
    }

    @Test
    void keptServersShowEvenWhenTheyAreNotFound() {
        NetworkPlaces.Tree tree = NetworkPlaces.build(List.of(),
                List.of(new SavedLocation("Cloud", NetworkAddress.parse("davs://ralf@cloud.example.org/remote.php/dav"), false, true, false),
                        new SavedLocation("", NetworkAddress.parse("smb://fritz.box/FRITZ.NAS"), true, true, false)),
                List.of(), Map.of());
        assertEquals(List.of("fritz.box", "Cloud", "Connect to Server…"), tree.top().stream().map(Place::name).toList());
        assertEquals(List.of("FRITZ.NAS"), tree.children().get(tree.top().getFirst().path()).stream().map(Place::name).toList());
    }
}
