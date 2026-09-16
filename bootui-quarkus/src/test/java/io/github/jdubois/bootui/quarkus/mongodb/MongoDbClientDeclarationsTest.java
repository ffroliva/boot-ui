package io.github.jdubois.bootui.quarkus.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.engine.mongodb.MongoDbSettings;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MongoDbClientDeclarationsTest {
    @Test
    void explicitNamedScopeOverridesInferredDatabaseWithoutReadingCredentialsOrUris() {
        var config = new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(
                        Map.of(
                                "quarkus.mongodb.database", "default_db",
                                "quarkus.mongodb.\"order.store\".database", "inferred",
                                "bootui.mongodb.clients.\"order.store\".databases", "orders,audit",
                                "quarkus.mongodb.\"order.store\".connection-string", "not-even-a-uri",
                                "quarkus.mongodb.\"inactive\".active", "false"),
                        "fixture",
                        1000))
                .build();
        var declarations = new MongoDbClientDeclarations(config, MongoDbSettings.defaults());
        assertThat(declarations.databases("default")).containsExactly("default_db");
        assertThat(declarations.databases("order.store")).containsExactly("orders", "audit");
        assertThat(declarations.inactive("inactive")).isTrue();
        assertThat(declarations.databases("missing")).isEmpty();
    }

    @Test
    void rejectsBlankDuplicateAndUnboundedScopes() {
        for (String raw : List.of("", " ", "one,", "one,one", "one, two", "one.", "one/", "a".repeat(64))) {
            assertThatThrownBy(() -> MongoDbClientDeclarations.parseDatabases(raw, MongoDbSettings.defaults()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(MongoDbClientDeclarations.parseDatabases("a,b,c,d,e,f,g,h,i", MongoDbSettings.defaults()))
                .hasSize(9);
    }

    @Test
    void unrelatedConfigurationCannotPreventValidMongoScopeDiscovery() {
        var values = new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i < 4097; i++) values.put("unrelated.setting." + i, "ordinary");
        values.put("bootui.mongodb.clients.client.databases", "orders,audit");
        var config = new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(values, "fixture", 1000))
                .build();
        assertThat(new MongoDbClientDeclarations(config, MongoDbSettings.defaults()).databases("client"))
                .containsExactly("orders", "audit");
    }
}
