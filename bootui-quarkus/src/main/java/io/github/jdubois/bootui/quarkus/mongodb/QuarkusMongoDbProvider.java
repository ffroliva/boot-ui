package io.github.jdubois.bootui.quarkus.mongodb;

import com.mongodb.connection.ClusterDescription;
import io.github.jdubois.bootui.core.dto.MongoDbServerDto;
import io.github.jdubois.bootui.core.dto.MongoDbTopologyDto;
import io.github.jdubois.bootui.spi.MongoDbClientAccess;
import io.github.jdubois.bootui.spi.MongoDbProvider;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InjectableBean;
import io.quarkus.arc.InjectableContext;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import java.util.ArrayList;
import java.util.List;

/** Observes existing contextual instances only. No client injection, supplier, internal map or health check. */
public final class QuarkusMongoDbProvider implements MongoDbProvider {
    private final MongoDbClientsSnapshot snapshot;
    private final MongoDbClientDeclarations configuration;
    private final ArcContainer container;

    public QuarkusMongoDbProvider(
            MongoDbClientsSnapshot snapshot, MongoDbClientDeclarations configuration, ArcContainer container) {
        this.snapshot = snapshot;
        this.configuration = configuration;
        this.container = container;
    }

    @Override
    public Discovery discover(int maxClients) {
        List<Client> clients = new ArrayList<>();
        for (var declaration : snapshot.declarations()) {
            if (clients.size() >= maxClients) break;
            clients.add(observe(declaration));
        }
        boolean truncated =
                snapshot.truncated() || clients.size() < snapshot.declarations().size();
        return new Discovery(clients, truncated ? List.of("ITEM_LIMIT") : List.of(), truncated);
    }

    private Client observe(MongoDbClientsSnapshot.Declaration declaration) {
        String lifecycle = "NOT_INITIALIZED";
        Object instance = null;
        MongoDbClientAccess access = null;
        MongoDbTopologyDto topology = null;
        List<String> limitations = new ArrayList<>();
        String version = null;
        try {
            if (configuration.inactive(declaration.name())) {
                lifecycle = "INACTIVE";
            } else if (declaration.removed()) {
                lifecycle = "UNRESOLVED";
                limitations.add("REMOVED");
            } else {
                InjectableBean<?> bean = container.bean(declaration.beanId());
                if (bean == null) {
                    lifecycle = "UNRESOLVED";
                } else if (!bean.isActive()) {
                    lifecycle = "INACTIVE";
                } else {
                    InjectableContext context = container.getActiveContext(bean.getScope());
                    // The one-argument Context.get is the CDI non-creating lookup.
                    instance = context == null ? null : context.get(bean);
                    if (instance instanceof com.mongodb.client.MongoClient sync
                            && instance.getClass().getName().equals("com.mongodb.client.internal.MongoClientImpl")) {
                        access = new QuarkusSyncMongoDbClientAccess(sync);
                        topology = topology(sync.getClusterDescription());
                        version = com.mongodb.client.MongoClient.class
                                .getPackage()
                                .getImplementationVersion();
                    } else if (instance instanceof ReactiveMongoClient reactive
                            && instance.getClass()
                                    .getName()
                                    .equals("io.quarkus.mongodb.impl.ReactiveMongoClientImpl")) {
                        var existing = reactive.unwrap();
                        access = new QuarkusReactiveMongoDbClientAccess(existing);
                        topology = topology(existing.getClusterDescription());
                        version = com.mongodb.reactivestreams.client.MongoClient.class
                                .getPackage()
                                .getImplementationVersion();
                    } else if (instance != null) {
                        lifecycle = "UNRESOLVED";
                        limitations.add("UNKNOWN_BINDING");
                    }
                    if (access != null) lifecycle = "INITIALIZED";
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            lifecycle = "UNRESOLVED";
            access = null;
            instance = null;
            topology = null;
            limitations.add("DISCOVERY_FAILED");
        }
        if (version == null) limitations.add("DRIVER_VERSION_UNAVAILABLE");
        List<String> configuredDatabases = configuration.databases(declaration.name());
        if (configuredDatabases.isEmpty()) limitations.add("NO_CONFIGURED_DATABASE");
        limitations.add(
                "Only settings exposed by the existing client API are observed; credentials and connection strings are excluded.");
        return new Client(
                declaration.beanId(),
                declaration.name(),
                declaration.driverStyle(),
                version,
                lifecycle,
                configuredDatabases,
                List.of(),
                topology,
                limitations,
                instance,
                access);
    }

    private static MongoDbTopologyDto topology(ClusterDescription description) {
        List<MongoDbServerDto> servers = new ArrayList<>();
        for (var server : description.getServerDescriptions()) {
            if (servers.size() == 32) break;
            servers.add(new MongoDbServerDto(
                    server.getAddress().toString(),
                    server.getType().name(),
                    server.getState().name()));
        }
        return new MongoDbTopologyDto(
                System.currentTimeMillis(),
                description.getType().name(),
                description.getConnectionMode().name(),
                servers,
                servers.size() < description.getServerDescriptions().size() ? List.of("ITEM_LIMIT") : List.of());
    }
}
