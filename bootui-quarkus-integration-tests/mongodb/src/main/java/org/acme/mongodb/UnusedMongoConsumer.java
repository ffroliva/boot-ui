package org.acme.mongodb;

import com.mongodb.client.MongoClient;
import io.quarkus.mongodb.MongoClientName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Intentionally removable application bean. Its injection point declares both native named styles. */
@ApplicationScoped
public class UnusedMongoConsumer {
    @Inject
    @MongoClientName("unused")
    MongoClient client;
}
