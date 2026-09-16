package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.sample.mongodb.SampleMongoProductRepository;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.sql.DataSource;
import org.bson.Document;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.boot.mongodb.autoconfigure.MongoConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/** Explicit -Pmongodb-sample,mongodb-live only. An unavailable Docker daemon is a failure, never a skip. */
@Testcontainers
@Import(DockerMongoDbProfileLiveTests.ConnectionConfiguration.class)
@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=docker-mongodb",
            "spring.docker.compose.enabled=false",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/mongodb-profile-test/overrides.properties"
        })
class DockerMongoDbProfileLiveTests {

    private static final String ROOT_PASSWORD = UUID.randomUUID().toString();
    private static final String APP_USERNAME =
            "app_" + UUID.randomUUID().toString().replace("-", "");
    private static final String APP_PASSWORD = UUID.randomUUID().toString();
    private static final ConcurrentLinkedQueue<String> COMMANDS = new ConcurrentLinkedQueue<>();

    @Container
    static final GenericContainer<?> MONGO = new GenericContainer<>("mongo:8.0.19")
            .withEnv("MONGO_INITDB_ROOT_USERNAME", "fixture_root")
            .withEnv("MONGO_INITDB_ROOT_PASSWORD", ROOT_PASSWORD)
            .withEnv("MONGO_INITDB_DATABASE", "bootui_sample")
            .withEnv("BOOTUI_SAMPLE_MONGODB_USERNAME", APP_USERNAME)
            .withEnv("BOOTUI_SAMPLE_MONGODB_PASSWORD", APP_PASSWORD)
            .withExposedPorts(27017)
            .withCreateContainerCmdModifier(command -> command.getHostConfig()
                    .withPortBindings(new PortBinding(Ports.Binding.bindIp("127.0.0.1"), new ExposedPort(27017))))
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("docker/mongodb/init.js")),
                    "/docker-entrypoint-initdb.d/01-bootui.js")
            .waitingFor(new WaitAllStrategy()
                    .withStrategy(Wait.forLogMessage(".*Waiting for connections.*\\n", 2))
                    .withStrategy(Wait.forSuccessfulCommand(
                            "mongosh --quiet --host 127.0.0.1 --eval \"const d = db.getSiblingDB('bootui_sample');"
                                    + " if (!d.auth(process.env.BOOTUI_SAMPLE_MONGODB_USERNAME,"
                                    + " process.env.BOOTUI_SAMPLE_MONGODB_PASSWORD)) quit(1);"
                                    + " if (d.runCommand({ping: 1}).ok !== 1) quit(1);\"")));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("sample.mongodb.username", () -> APP_USERNAME);
        registry.add("sample.mongodb.password", () -> APP_PASSWORD);
    }

    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext context;

    @Autowired
    MongoClient mongo;

    @Autowired
    MongoTemplate template;

    @Autowired
    SampleMongoProductRepository repository;

    @Autowired
    DataSource dataSource;

    @Autowired
    Flyway flyway;

    @Test
    void nonRootWorkloadAndExplicitInspectionCoexistWithUnchangedRelationalFeatures() throws Exception {
        assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
        assertThat(context.getBeansOfType(MongoClient.class)).hasSize(1);
        assertThat(context.getBeansOfType(KafkaTemplate.class)).isEmpty();
        assertThat(context.getBeansOfType(ChatModel.class)).isEmpty();
        assertThat(context.getBeansOfType(EmbeddingModel.class)).isEmpty();
        assertThat(context.getBeansOfType(RedisConnectionFactory.class)).isEmpty();
        assertThat(context.getBean(CacheManager.class)
                        .getCache("sample-products")
                        .getNativeCache())
                .isInstanceOf(com.github.benmanes.caffeine.cache.Cache.class);
        assertThat(template.getDb().getName()).isEqualTo("bootui_sample");
        assertThat(repository).isNotNull();
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("H2");
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("select count(*) from sample_products")) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(3);
            }
        }
        assertThat(flyway.info().pending()).hasSize(2);

        var authenticated = mongo.getDatabase("bootui_sample")
                .runCommand(new Document("connectionStatus", 1))
                .get("authInfo", Document.class)
                .getList("authenticatedUsers", Document.class);
        assertThat(authenticated).containsExactly(new Document("user", APP_USERNAME).append("db", "bootui_sample"));
        try (var admin = MongoClients.create(rootConnectionString())) {
            var users = admin.getDatabase("bootui_sample")
                    .runCommand(new Document("usersInfo", APP_USERNAME))
                    .getList("users", Document.class);
            assertThat(users)
                    .singleElement()
                    .satisfies(user -> assertThat(user.getList("roles", Document.class))
                            .containsExactly(new Document("role", "readWrite").append("db", "bootui_sample")));
        }

        String origin = "http://127.0.0.1:" + port;
        var probe = new BootUiHttpProbe(origin);
        COMMANDS.clear();
        var panels = probe.get("/bootui/api/panels");
        assertThat(panels.status()).isEqualTo(200);
        assertThat(panels.json().path("panels")).anySatisfy(panel -> {
            assertThat(panel.path("id").asText()).isEqualTo("mongodb");
            assertThat(panel.path("available").asBoolean()).isTrue();
        });
        var initial = probe.get("/bootui/api/mongodb");
        assertThat(initial.status()).isEqualTo(200);
        assertThat(initial.json().path("status").asText()).isEqualTo("NOT_READ");
        assertThat(COMMANDS)
                .as("Discovery and cached reports must issue no Mongo commands")
                .isEmpty();
        io.github.jdubois.bootui.conformance.MongoDbProcessContract.browser(origin, "/bootui", false);
        assertThat(COMMANDS)
                .as("Opening the real MongoDB browser page must issue no Mongo commands")
                .isEmpty();
        assertThat(probe.get("/api/sample/products").body()).contains("BootUI Starter");
        assertThat(probe.get("/api/sample/product-search?term=BootUI").status()).isEqualTo(200);
        assertThat(probe.get("/bootui/api/liquibase/changesets").json().path("databases"))
                .anySatisfy(
                        database -> assertThat(database.path("pending").asInt()).isEqualTo(2));

        assertThat(probe.post("/api/sample/mongodb/workload", Map.of()).status())
                .isEqualTo(403);
        var csrf = probe.get("/api/sample/mongodb/csrf").json();
        var workload = probe.post(
                "/api/sample/mongodb/workload",
                Map.of(
                        "Origin",
                        origin,
                        csrf.path("headerName").asText(),
                        csrf.path("token").asText()));
        assertThat(workload.status()).isEqualTo(200);
        assertThat(workload.json().path("upserted").asInt()).isEqualTo(3);
        assertThat(workload.json().path("availableProducts").asInt()).isEqualTo(2);
        assertThat(workload.json().path("categories")).hasSize(2);
        assertThat(COMMANDS).contains("find", "aggregate");

        String clientId = initial.json().at("/inventory/clients/0/id").asText();
        assertThat(clientId).isNotBlank();
        var inspection = probe.request(
                "POST",
                "/bootui/api/mongodb/inspect",
                Map.of(
                        "Origin",
                        origin,
                        "Content-Type",
                        "application/json",
                        "X-XSRF-TOKEN",
                        probe.cookie("XSRF-TOKEN").orElseThrow()),
                "{\"clientId\":\"" + clientId + "\",\"scope\":\"CONFIGURED\"}");
        assertThat(inspection.status()).isEqualTo(200);
        assertThat(inspection.json().at("/inspection/snapshotId").asText()).isNotBlank();
        assertThat(inspection.json().at("/inspection/collectionsRetained").asInt())
                .isGreaterThanOrEqualTo(2);
        assertThat(inspection.json().at("/inspection/indexesRetained").asInt()).isGreaterThanOrEqualTo(6);
        assertThat(inspection.body())
                .doesNotContain(
                        APP_PASSWORD, ROOT_PASSWORD, "BOOTUI_PARTIAL_LITERAL_MUST_NOT_LEAK", "Document starter");
        assertThat(COMMANDS).contains("listCollections", "listIndexes");
        io.github.jdubois.bootui.conformance.MongoDbProcessContract.browser(origin, "/bootui", true);
        assertThat(flyway.info().pending()).hasSize(2);
    }

    private static String rootConnectionString() {
        return "mongodb://fixture_root:" + ROOT_PASSWORD + "@" + MONGO.getHost() + ":" + MONGO.getMappedPort(27017)
                + "/bootui_sample?authSource=admin&serverSelectionTimeoutMS=2000&timeoutMS=2000";
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ConnectionConfiguration {

        @Bean
        MongoConnectionDetails fixtureConnectionDetails() {
            // Same endpoint/credential shape as Boot 4's Compose factory, including root and authSource=admin.
            return () -> new ConnectionString(rootConnectionString());
        }

        @Bean
        MongoClientSettingsBuilderCustomizer captureSampleCommands() {
            return builder -> builder.addCommandListener(new CommandListener() {
                @Override
                public void commandStarted(CommandStartedEvent event) {
                    COMMANDS.add(event.getCommandName());
                }
            });
        }
    }
}
