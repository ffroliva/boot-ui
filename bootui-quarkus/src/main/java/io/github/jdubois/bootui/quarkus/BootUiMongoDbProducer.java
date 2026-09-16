package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.engine.mongodb.MongoDbSettings;
import io.github.jdubois.bootui.quarkus.mongodb.MongoDbClientDeclarations;
import io.github.jdubois.bootui.quarkus.mongodb.MongoDbClientsSnapshot;
import io.github.jdubois.bootui.quarkus.mongodb.QuarkusMongoDbProvider;
import io.github.jdubois.bootui.spi.MongoDbProvider;
import io.quarkus.arc.Arc;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.Config;

/** Capability-gated construction; deliberately has no Mongo client injection point. */
public class BootUiMongoDbProducer {
    void validateScopes(
            @jakarta.enterprise.event.Observes io.quarkus.runtime.StartupEvent event,
            MongoDbClientDeclarations scopes) {}

    @Produces
    @Singleton
    public MongoDbClientDeclarations mongoDbScopes(Config config, MongoDbSettings settings) {
        return new MongoDbClientDeclarations(config, settings);
    }

    @Produces
    @Singleton
    public MongoDbProvider mongoDbProvider(MongoDbClientsSnapshot snapshot, MongoDbClientDeclarations scopes) {
        return new QuarkusMongoDbProvider(snapshot, scopes, Arc.container());
    }
}
