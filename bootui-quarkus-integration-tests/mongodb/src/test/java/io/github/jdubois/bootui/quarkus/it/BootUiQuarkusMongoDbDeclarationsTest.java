package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.quarkus.mongodb.MongoDbClientsSnapshot;
import io.github.jdubois.bootui.spi.MongoDbProvider;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URL;
import org.junit.jupiter.api.Test;

@QuarkusTest
class BootUiQuarkusMongoDbDeclarationsTest {
    @TestHTTPResource
    URL baseUrl;

    @Inject
    MongoDbClientsSnapshot declarations;

    @Inject
    MongoDbProvider provider;

    @Test
    void nativeAndInactiveDeclarationsStayNonCreatingWithoutDocker() {
        assertThat(declarations.declarations()).anyMatch(row -> row.name().equals("unused"));
        var before = provider.discover(16).clients();
        assertThat(before)
                .anyMatch(client ->
                        client.name().equals("inactive") && client.lifecycle().equals("INACTIVE"));
        assertThat(before)
                .filteredOn(client -> client.name().equals("inactive"))
                .allMatch(client -> client.access() == null);
        BootUiHttpProbe probe = new BootUiHttpProbe(baseUrl.toExternalForm());
        var report = probe.get("/bootui/api/mongodb");
        assertThat(report.status()).isEqualTo(200);
        assertThat(report.json().path("available").asBoolean()).isTrue();
        assertThat(report.json().path("status").asText()).isEqualTo("NOT_READ");
        assertThat(report.json().path("inventory").path("clients")).isNotEmpty();
        assertThat(report.json().toString()).doesNotContain("mongodb://");
        var after = provider.discover(16).clients();
        assertThat(after).hasSize(before.size());
        for (var client : after) {
            assertThat(client.identity())
                    .isSameAs(before.stream()
                            .filter(row -> row.key().equals(client.key()))
                            .findFirst()
                            .orElseThrow()
                            .identity());
        }
    }
}
