package io.github.jdubois.bootui.autoconfigure.mongodb;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.TimeSeriesOptions;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.event.ConnectionPoolCreatedEvent;
import com.mongodb.event.ConnectionPoolListener;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/** Opted-in owned fixture: Docker absence fails this lane; production code never installs listeners. */
final class MongoDbLiveFixture implements AutoCloseable {
    static final String DATABASE = "bootui_fixture";
    private final String password = UUID.randomUUID().toString();
    final GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse("mongo:8.0.19"))
            .withEnv("MONGO_INITDB_ROOT_USERNAME", "fixtureAdmin")
            .withEnv("MONGO_INITDB_ROOT_PASSWORD", password)
            .withExposedPorts(27017)
            .withCommand("mongod", "--bind_ip_all", "--auth", "--setParameter", "enableTestCommands=1")
            .waitingFor(Wait.forListeningPort());
    final List<String> commands = new CopyOnWriteArrayList<>();
    final AtomicInteger pools = new AtomicInteger();

    MongoDbLiveFixture() {
        container.start();
        try (MongoClient admin = admin()) {
            var database = admin.getDatabase(DATABASE);
            database.createCollection("orders");
            database.createCollection("empty");
            for (int index = 0; index < 24; index++) database.createCollection("catalog_" + index);
            database.createCollection(
                    "measurements", new CreateCollectionOptions().timeSeriesOptions(new TimeSeriesOptions("time")));
            database.getCollection("orders").insertOne(new Document("tenant", "fixture").append("created", 1));
            database.getCollection("orders")
                    .createIndex(
                            Indexes.compoundIndex(Indexes.ascending("tenant"), Indexes.descending("created")),
                            new IndexOptions().name("tenant_created").unique(true));
            database.getCollection("orders")
                    .createIndex(
                            Indexes.ascending("expires"),
                            new IndexOptions().name("expires_ttl").expireAfter(3600L, TimeUnit.SECONDS));
            database.getCollection("orders")
                    .createIndex(Indexes.hashed("tenant"), new IndexOptions().name("tenant_hashed"));
            database.getCollection("orders")
                    .createIndex(Indexes.ascending("attributes.$**"), new IndexOptions().name("attributes_wildcard"));
            database.createView("order_view", "orders", List.of(new Document("$project", new Document("tenant", 1))));
            database.runCommand(new Document("createUser", "application")
                    .append("pwd", password)
                    .append("roles", List.of(new Document("role", "read").append("db", DATABASE))));
            database.runCommand(new Document("createRole", "namesOnly")
                    .append(
                            "privileges",
                            List.of(new Document(
                                            "resource", new Document("db", DATABASE).append("collection", "orders"))
                                    .append("actions", List.of("find"))))
                    .append("roles", List.of()));
            database.runCommand(new Document("createUser", "restricted")
                    .append("pwd", password)
                    .append("roles", List.of(new Document("role", "namesOnly").append("db", DATABASE))));
        } catch (RuntimeException | Error ex) {
            container.close();
            throw ex;
        }
    }

    MongoClient admin() {
        return MongoClients.create(MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(address()))
                .credential(MongoCredential.createCredential("fixtureAdmin", "admin", password.toCharArray()))
                .timeout(10, TimeUnit.SECONDS)
                .build());
    }

    MongoClient sync(String user, long timeoutMillis) {
        return MongoClients.create(settings(user, timeoutMillis));
    }

    MongoClient sync(String user, long timeoutMillis, CodecRegistry registry) {
        return MongoClients.create(MongoClientSettings.builder(settings(user, timeoutMillis))
                .codecRegistry(registry)
                .build());
    }

    com.mongodb.reactivestreams.client.MongoClient reactive(String user, long timeoutMillis) {
        return com.mongodb.reactivestreams.client.MongoClients.create(settings(user, timeoutMillis));
    }

    com.mongodb.reactivestreams.client.MongoClient reactive(String user, long timeoutMillis, CodecRegistry registry) {
        return com.mongodb.reactivestreams.client.MongoClients.create(
                MongoClientSettings.builder(settings(user, timeoutMillis))
                        .codecRegistry(registry)
                        .build());
    }

    private MongoClientSettings settings(String user, long timeoutMillis) {
        return MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(address()))
                .credential(MongoCredential.createCredential(user, DATABASE, password.toCharArray()))
                .timeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .addCommandListener(new CommandListener() {
                    @Override
                    public void commandStarted(CommandStartedEvent event) {
                        commands.add(event.getCommandName());
                    }
                })
                .applyToConnectionPoolSettings(pool -> pool.addConnectionPoolListener(new ConnectionPoolListener() {
                    @Override
                    public void connectionPoolCreated(ConnectionPoolCreatedEvent event) {
                        pools.incrementAndGet();
                    }
                }))
                .build();
    }

    private String address() {
        return "mongodb://" + container.getHost() + ":" + container.getMappedPort(27017) + "/?directConnection=true";
    }

    void blockCatalog(boolean enabled) {
        try (MongoClient admin = admin()) {
            admin.getDatabase("admin")
                    .runCommand(new Document("configureFailPoint", "failCommand")
                            .append("mode", enabled ? "alwaysOn" : "off")
                            .append(
                                    "data",
                                    new Document("failCommands", List.of("listCollections", "listDatabases"))
                                            .append("blockConnection", true)
                                            .append("blockTimeMS", 2000)));
        }
    }

    @Override
    public void close() {
        container.close();
    }
}
