package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import org.junit.jupiter.api.Test;

@QuarkusTest
class BootUiQuarkusMongoDbWithoutExtensionTest {
    @TestHTTPResource
    URL baseUrl;

    @Test
    void noMongoExtensionOrDriversAreRequiredForTheUnavailableReport() {
        for (String type : new String[] {
            "com.mongodb.client.MongoClient",
            "com.mongodb.reactivestreams.client.MongoClient",
            "io.quarkus.mongodb.reactive.ReactiveMongoClient"
        }) {
            assertThatThrownBy(() ->
                            Class.forName(type, false, Thread.currentThread().getContextClassLoader()))
                    .isInstanceOf(ClassNotFoundException.class);
        }
        var probe = new BootUiHttpProbe(baseUrl.toExternalForm());
        var response = probe.get("/bootui/api/mongodb");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().path("available").asBoolean()).isFalse();
        assertThat(response.json().path("inventory").path("clients")).isEmpty();
        for (var panel : probe.get("/bootui/api/panels").json().path("panels")) {
            if ("mongodb".equals(panel.path("id").asText())) {
                assertThat(panel.path("available").asBoolean()).isFalse();
                assertThat(panel.path("unavailableReason").asText()).contains("quarkus-mongodb-client");
            }
        }
    }
}
