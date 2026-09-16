package io.github.jdubois.bootui.quarkus.it;

import com.mongodb.client.MongoClient;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.mongodb.MongoClientName;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Application-owned injections: BootUI must observe these exact native instances, not counterparts. */
@ApplicationScoped
@Startup
@IfBuildProfile("mongodb-live")
public class MongoDbLiveClients {
    @Inject
    MongoClient sync;

    @Inject
    ReactiveMongoClient reactive;

    @Inject
    @MongoClientName("named")
    MongoClient namedSync;

    @Inject
    @MongoClientName("named")
    ReactiveMongoClient namedReactive;

    @Inject
    @MongoClientName("syncOnly")
    MongoClient syncOnly;

    @Inject
    @MongoClientName("reactiveOnly")
    ReactiveMongoClient reactiveOnly;

    @Inject
    @MongoClientName("restricted")
    MongoClient restricted;

    @Inject
    @MongoClientName("timeout")
    MongoClient timeoutSync;

    @Inject
    @MongoClientName("timeout")
    ReactiveMongoClient timeoutReactive;

    public MongoClient sync() {
        return sync;
    }

    public ReactiveMongoClient reactive() {
        return reactive;
    }

    public MongoClient restricted() {
        return restricted;
    }

    public MongoClient timeoutSync() {
        return timeoutSync;
    }

    public ReactiveMongoClient timeoutReactive() {
        return timeoutReactive;
    }
}
