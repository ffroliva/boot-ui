package io.github.jdubois.bootui.spi;

import io.github.jdubois.bootui.core.dto.MongoDbSettingDto;
import io.github.jdubois.bootui.core.dto.MongoDbTopologyDto;
import java.util.List;

/** No-I/O/non-creating declaration inventory. Framework and driver types remain adapter-private. */
public interface MongoDbProvider {
    Discovery discover(int maxClients);

    record Discovery(List<Client> clients, List<String> limitations, boolean truncated) {
        public Discovery {
            clients = List.copyOf(clients);
            limitations = List.copyOf(limitations);
        }
    }

    /**
     * key is an internal stable bean/qualifier identity, never a URI. access is null for unresolved,
     * inactive or uninitialized clients. identity is the existing object identity for replacement detection.
     */
    record Client(
            String key,
            String name,
            String driverStyle,
            String driverVersion,
            String lifecycle,
            List<String> databases,
            List<MongoDbSettingDto> settings,
            MongoDbTopologyDto topology,
            List<String> limitations,
            Object identity,
            MongoDbClientAccess access) {
        public Client {
            databases = List.copyOf(databases);
            settings = List.copyOf(settings);
            limitations = List.copyOf(limitations);
        }
    }
}
