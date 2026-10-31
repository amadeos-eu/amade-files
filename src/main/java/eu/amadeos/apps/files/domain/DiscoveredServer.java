package eu.amadeos.apps.files.domain;

/**
 * A server that announced itself on the network (mDNS/DNS-SD or WS-Discovery), as gvfs lists it in
 * {@code network:///}.
 *
 * @param name    the name it announces, such as {@code MyCloudEX2Ultra}
 * @param address where to reach it, without a path
 */
public record DiscoveredServer(String name, NetworkAddress address) {
}
