package io.github.jdubois.bootui.quarkus.it;

import com.mongodb.client.MongoClients;
import com.mongodb.client.model.IndexOptions;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/** Required authenticated fixture; opt-in failure is a failure, never disabledWithoutDocker. */
public final class MongoDbLiveResource implements QuarkusTestResourceLifecycleManager {
    public static final String IMAGE = "mongo:8.0.19";
    public static final String DATABASE = "fixture_catalog";
    private GenericContainer<?> mongo;
    private String rootPassword;
    private String readerPassword;

    @Override
    public Map<String, String> start() {
        rootPassword = UUID.randomUUID().toString();
        readerPassword = UUID.randomUUID().toString();
        mongo = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withEnv("MONGO_INITDB_ROOT_USERNAME", "fixture_root")
                .withEnv("MONGO_INITDB_ROOT_PASSWORD", rootPassword)
                .withExposedPorts(27017)
                .withCommand("mongod", "--auth", "--bind_ip_all", "--setParameter", "enableTestCommands=1")
                .waitingFor(Wait.forListeningPort())
                .withStartupTimeout(Duration.ofMinutes(2));
        try {
            mongo.start();
            prepare();
            Map<String, String> config = new LinkedHashMap<>();
            config.put("quarkus.mongodb.connection-string", uri("fixture_reader", readerPassword, DATABASE));
            config.put("quarkus.mongodb.database", DATABASE);
            for (String name : List.of("named", "syncOnly", "reactiveOnly")) {
                config.put(
                        "quarkus.mongodb.\"" + name + "\".connection-string",
                        uri("fixture_reader", readerPassword, DATABASE));
                config.put("quarkus.mongodb.\"" + name + "\".database", DATABASE);
            }
            config.put(
                    "quarkus.mongodb.\"restricted\".connection-string",
                    uri("fixture_restricted", readerPassword, DATABASE));
            config.put("quarkus.mongodb.\"restricted\".database", DATABASE);
            config.put(
                    "quarkus.mongodb.\"timeout\".connection-string", uri("fixture_reader", readerPassword, DATABASE));
            config.put("quarkus.mongodb.\"timeout\".database", DATABASE);
            config.put("quarkus.mongodb.\"timeout\".application-name", "bootui-timeout-fixture");
            config.put("bootui.mongodb.authorized-database-enumeration-enabled", "true");
            config.put("bootui.mcp.enabled", "ON");
            config.put("bootui.expose-values", "MASKED");
            config.put("bootui.fixture.mongo-admin-password-uri", uri("fixture_root", rootPassword, "admin"));
            return config;
        } catch (RuntimeException failure) {
            stop();
            throw new IllegalStateException("Required authenticated MongoDB fixture could not start");
        }
    }

    private String uri(String user, String password, String authDatabase) {
        return "mongodb://" + user + ":" + password + "@" + mongo.getHost() + ":" + mongo.getMappedPort(27017) + "/"
                + authDatabase + "?authSource=" + authDatabase + "&serverSelectionTimeoutMS=5000";
    }

    private void prepare() {
        try (var client = MongoClients.create(uri("fixture_root", rootPassword, "admin"))) {
            var database = client.getDatabase(DATABASE);
            database.createCollection("fixture_orders");
            var collection = database.getCollection("fixture_orders");
            collection.insertOne(new Document("region", "fixture")
                    .append("placed", 1)
                    .append("document_secret", "must-never-be-observed"));
            collection.createIndex(
                    new Document("region", 1).append("placed", -1), new IndexOptions().name("fixture_compound"));
            collection.createIndex(new Document("region", "hashed"), new IndexOptions().name("fixture_hashed"));
            collection.createIndex(new Document("attributes.$**", 1), new IndexOptions().name("fixture_wildcard"));
            database.createCollection("fixture_empty");
            database.runCommand(new Document("create", "fixture_view")
                    .append("viewOn", "fixture_orders")
                    .append("pipeline", List.of()));
            for (int index = 0; index < 25; index++) database.createCollection("fixture_extra_" + index);
            database.runCommand(new Document("createUser", "fixture_reader")
                    .append("pwd", readerPassword)
                    .append("roles", List.of(new Document("role", "read").append("db", DATABASE))));
            database.runCommand(new Document("createRole", "fixture_names_only")
                    .append(
                            "privileges",
                            List.of(new Document(
                                            "resource",
                                            new Document("db", DATABASE).append("collection", "fixture_orders"))
                                    .append("actions", List.of("find"))))
                    .append("roles", List.of()));
            database.runCommand(new Document("createUser", "fixture_restricted")
                    .append("pwd", readerPassword)
                    .append("roles", List.of(new Document("role", "fixture_names_only").append("db", DATABASE))));
        }
    }

    @Override
    public void stop() {
        if (mongo != null) {
            mongo.stop();
            mongo = null;
        }
        rootPassword = null;
        readerPassword = null;
    }
}
