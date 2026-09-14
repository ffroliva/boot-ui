package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DockerMySqlProfileTests {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void mysqlProfileReusesDockerServicesAndSelectsItsOwnDatabaseAndMigrations() {
        runner.withPropertyValues("spring.profiles.active=docker-mysql").run(context -> {
            assertThat(context).hasNotFailed();
            var environment = context.getEnvironment();
            assertThat(environment.getProperty("spring.docker.compose.enabled")).isEqualTo("true");
            assertThat(environment.getProperty("spring.docker.compose.file")).isEqualTo("compose-mysql.yaml");
            assertThat(environment.getProperty("spring.datasource.driver-class-name"))
                    .isEqualTo("com.mysql.cj.jdbc.Driver");
            assertThat(environment.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration-mysql");
            assertThat(environment.getProperty("spring.cache.type")).isEqualTo("redis");
            assertThat(environment.getProperty("spring.kafka.bootstrap-servers"))
                    .isEqualTo("localhost:9092");
            assertThat(environment.getProperty("spring.ai.ollama.base-url")).isEqualTo("http://localhost:11434");
            assertThat(environment.getProperty("bootui.enabled-profiles")).contains("docker-mysql");
        });
    }

    @Test
    void existingDockerAndDockerFreeProfilesKeepTheirDatabaseConfiguration() {
        runner.withPropertyValues("spring.profiles.active=docker").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("spring.docker.compose.enabled"))
                    .isEqualTo("true");
            assertThat(context.getEnvironment().getProperty("spring.docker.compose.file"))
                    .isNull();
            assertThat(context.getEnvironment().getProperty("spring.flyway.locations"))
                    .isNull();
        });
        runner.withPropertyValues("spring.profiles.active=dev").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("spring.docker.compose.enabled"))
                    .isEqualTo("false");
            assertThat(context.getEnvironment().getProperty("spring.datasource.url"))
                    .startsWith("jdbc:h2:");
            assertThat(context.getEnvironment().getProperty("spring.flyway.locations"))
                    .isNull();
        });
    }
}
