package org.acme.mongodb;

import com.mongodb.client.MongoClient;
import io.quarkus.arc.InjectableInstance;
import io.quarkus.arc.Unremovable;
import io.quarkus.mongodb.MongoClientName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Dynamic injection is legal for inactive clients, and must not activate either driver style. */
@ApplicationScoped
@Unremovable
public class InactiveMongoConsumer {
    @Inject
    @MongoClientName("inactive")
    InjectableInstance<MongoClient> client;
}
