package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.engine.mongodb.MongoDbSettings;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BootUiEngineProducerMongoDbConfigTest {
    @Test
    void defaultsAndOverridesUseTheSharedStrictParser() {
        var producer = new BootUiEngineProducer();
        assertThat(producer.mongoDbSettings(config(Map.of()))).isEqualTo(MongoDbSettings.defaults());
        assertThat(producer.mongoDbSettings(config(Map.of("bootui.mongodb.max-clients", "3")))
                        .maxClients())
                .isEqualTo(3);
    }

    @Test
    void invalidAndBlankActiveConfigurationFailsAtStartup() {
        for (String invalid : List.of("", " ", "0", "-1", "65", "2147483648", "2.5")) {
            assertThatThrownBy(() -> new BootUiEngineProducer()
                            .validateMongoDbSettings(null, config(Map.of("bootui.mongodb.max-clients", invalid))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("bootui.mongodb.max-clients");
        }
    }

    private static io.smallrye.config.SmallRyeConfig config(Map<String, String> values) {
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(values, "fixture", 1000))
                .build();
    }
}
