package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Tests the actual primary-database profile against MySQL, without starting unrelated Kafka/Redis/AI
 * services. The Compose database image, initialization grants and instrumentation are used unchanged.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=docker-mysql",
            "spring.docker.compose.enabled=false",
            "spring.cache.type=simple",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
                    + "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration,"
                    + "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration,"
                    + "org.springframework.boot.data.redis.autoconfigure.health.DataRedisHealthContributorAutoConfiguration,"
                    + "org.springframework.boot.data.redis.autoconfigure.health.DataRedisReactiveHealthContributorAutoConfiguration,"
                    + "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration,"
                    + "org.springframework.boot.kafka.autoconfigure.metrics.KafkaMetricsAutoConfiguration,"
                    + "org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration,"
                    + "org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration,"
                    + "org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/docker-mysql-profile-test/overrides.properties"
        })
class DockerMySqlProfileLiveTests {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.6")
            .withDatabaseName("bootui_sample")
            .withUsername("bootui")
            .withPassword("bootui")
            .withCommand(
                    "--performance-schema=ON",
                    "--performance-schema-consumer-statements-digest=ON",
                    "--performance-schema-instrument=statement/sql/%=ON",
                    "--performance-schema-instrument=wait/io/table/sql/handler=ON")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("docker/mysql/init.sql")),
                    "/docker-entrypoint-initdb.d/01-bootui-diagnostics.sql");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    ApplicationContext context;

    @Autowired
    Flyway flyway;

    @Autowired
    SpringLiquibase liquibase;

    @Test
    void primaryMysqlRunsJpaBothMigrationToolsAndTheOperationalPanel() throws Exception {
        assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            assertThat(count(connection, "sample_products")).isEqualTo(3);
            assertThat(count(connection, "catalog_book")).isEqualTo(2);
            assertThat(count(connection, "inventory_warehouse")).isEqualTo(1);
        }
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");
        assertThat(flyway.info().pending()).hasSize(2);

        String origin = "http://127.0.0.1:" + port;
        BootUiHttpProbe probe = new BootUiHttpProbe(origin);
        var panels = probe.get("/bootui/api/panels").json().path("panels");
        assertThat(panels).anySatisfy(panel -> {
            assertThat(panel.path("id").asText()).isEqualTo("mysql");
            assertThat(panel.path("available").asBoolean()).isTrue();
        });
        assertThat(panels).anySatisfy(panel -> {
            assertThat(panel.path("id").asText()).isEqualTo("postgresql");
            assertThat(panel.path("available").asBoolean()).isFalse();
        });
        assertThat(probe.get("/bootui/api/mysql").json().path("status").asText())
                .isEqualTo("NOT_READ");
        var products = probe.get("/api/sample/products");
        assertThat(products.status()).isEqualTo(200);
        assertThat(products.body()).contains("BootUI Starter");

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", origin);
        probe.cookie("XSRF-TOKEN").ifPresent(token -> headers.put("X-XSRF-TOKEN", token));
        var response = probe.post("/bootui/api/mysql/read", headers);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().path("status").asText())
                .as(
                        "MySQL sections: %s; limits: %s",
                        response.json().at("/dataSources/0/sections"),
                        response.json().path("limitations"))
                .isEqualTo(response.json().path("truncated").asBoolean() ? "PARTIAL" : "READ");
        var source = response.json().path("dataSources").get(0);
        assertThat(source.path("schemaName").asText()).isEqualTo("bootui_sample");
        assertThat(source.path("message").isNull()).isTrue();
        assertThat(source.path("sections")).hasSize(8).allSatisfy(section -> {
            assertThat(section.path("status").asText()).isEqualTo("AVAILABLE");
            assertThat(section.path("reason").isNull()).isTrue();
        });
        assertThat(source.path("tables"))
                .anyMatch(table ->
                        "sample_products".equals(table.path("tableName").asText()));
        assertThat(source.path("statements"))
                .anyMatch(statement -> statement.path("digestText").asText().contains("sample_products"));

        flyway.migrate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("4");
        assertThat(flyway.info().pending()).isEmpty();
        liquibase.setShouldRun(true);
        liquibase.afterPropertiesSet();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            assertThat(count(connection, "catalog_tag")).isEqualTo(2);
            assertThat(count(connection, "catalog_book_tag")).isEqualTo(3);
            assertThat(count(connection, "inventory_item")).isEqualTo(2);
            assertThatThrownBy(() -> statement.executeUpdate(
                            "insert into catalog_book (title, author_id) values ('invalid fixture', 9999)"))
                    .isInstanceOf(SQLIntegrityConstraintViolationException.class);
            assertThatThrownBy(() ->
                            statement.executeUpdate("insert into catalog_book_tag (book_id, tag_id) values (9999, 1)"))
                    .isInstanceOf(SQLIntegrityConstraintViolationException.class);
        }
    }

    private static long count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
                var result = statement.executeQuery("select count(*) from " + table)) {
            result.next();
            return result.getLong(1);
        }
    }
}
