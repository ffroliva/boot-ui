package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;

class DockerMongoDbProfileTests {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void composeHasOneAuthenticatedLocalMongoServiceAndNoOtherInfrastructure() {
        YamlMapFactoryBean yaml = new YamlMapFactoryBean();
        yaml.setResources(new FileSystemResource("compose-mongodb.yaml"));
        assertThat(yaml.getObject()).containsEntry("name", "bootui-spring-sample-mongodb");
        assertThat(yaml.getObject())
                .extractingByKey("services")
                .asInstanceOf(MAP)
                .containsOnlyKeys("mongodb")
                .extractingByKey("mongodb")
                .asInstanceOf(MAP)
                .containsEntry("image", "mongo:8.0.19")
                .containsEntry("ports", java.util.List.of("127.0.0.1::27017"))
                .containsEntry(
                        "volumes",
                        java.util.List.of("./docker/mongodb/init.js:/docker-entrypoint-initdb.d/01-bootui.js:ro"));
    }

    @Test
    void profileKeepsH2MigrationsAndCaffeineWithoutKafkaRedisOrAi() {
        runner.withPropertyValues("spring.profiles.active=docker-mongodb").run(context -> {
            assertThat(context).hasNotFailed();
            var environment = context.getEnvironment();
            assertThat(environment.getProperty("spring.docker.compose.file")).isEqualTo("compose-mongodb.yaml");
            assertThat(environment.getProperty("spring.datasource.url")).startsWith("jdbc:h2:");
            assertThat(environment.getProperty("spring.datasource.driver-class-name"))
                    .isEqualTo("org.h2.Driver");
            assertThat(environment.getProperty("spring.cache.type")).isEqualTo("caffeine");
            assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("true");
            assertThat(environment.getProperty("spring.flyway.locations")).isNull();
            assertThat(environment.getProperty("spring.liquibase.enabled")).isEqualTo("true");
            assertThat(environment.getProperty("spring.mongodb.database")).isEqualTo("bootui_sample");
            assertThat(environment.getProperty("spring.data.mongodb.auto-index-creation"))
                    .isEqualTo("false");
            assertThat(environment.getProperty("bootui.mongodb.clients.sampleMongoClient.databases"))
                    .isEqualTo("bootui_sample");
            assertThat(environment.getProperty("bootui.enabled-profiles")).contains("docker-mongodb");
            assertThat(environment.getProperty("spring.autoconfigure.exclude", String[].class))
                    .contains(
                            "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
                            "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration",
                            "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
                            "org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration",
                            "org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration");
        });
    }

    @Test
    void mongoDependenciesSourcesAndLiveTestsAreOptIn() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var pom = factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        var xpath = XPathFactory.newInstance().newXPath();
        assertThat(xpath.evaluate("count(/project/dependencies/dependency[contains(artifactId, 'mongodb')])", pom))
                .isEqualTo("0");
        assertThat(xpath.evaluate(
                        "count(/project/profiles/profile[id='mongodb-sample']/dependencies/dependency"
                                + "[artifactId='spring-boot-starter-data-mongodb'])",
                        pom))
                .isEqualTo("1");
        assertThat(xpath.evaluate("count(/project/profiles/profile[id='mongodb-sample']/activation)", pom))
                .isEqualTo("0");
        assertThat(Files.readString(Path.of("pom.xml")))
                .contains(
                        "src/mongodb/java", "src/mongodb-test/java", "<id>mongodb-live</id>", "src/mongodb-live/java");
        assertThat(Files.readString(Path.of("run-local-mongodb.sh")))
                .contains("-Pmongodb-sample", "-Dspring-boot.run.profiles=docker-mongodb", "\"$@\"");
        assertThat(Files.readString(Path.of("docker/mongodb/init.js")))
                .contains("readWrite", "bootui_sample", "expireAfterSeconds", "partialFilterExpression")
                .doesNotContain("clusterAdmin", "root'");
    }

    @Test
    void defaultProfileDoesNotConfigureMongoOrCompose() {
        runner.withPropertyValues("spring.profiles.active=dev").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("spring.docker.compose.enabled"))
                    .isEqualTo("false");
            assertThat(context.getEnvironment().getProperty("spring.mongodb.uri"))
                    .isNull();
            assertThat(context.getEnvironment().getProperty("sample.mongodb.username"))
                    .isNull();
        });
    }
}
