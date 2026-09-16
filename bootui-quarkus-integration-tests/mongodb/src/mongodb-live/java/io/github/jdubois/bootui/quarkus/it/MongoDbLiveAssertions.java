package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Adapter fixture assertions; the shared positive transport contract can be invoked beside verify. */
final class MongoDbLiveAssertions {
    static final Map<String, String> JSON = Map.of("Content-Type", "application/json");

    private MongoDbLiveAssertions() {}

    static JsonNode verify(BootUiHttpProbe probe, String api, String clientName, String style) {
        var initial = probe.get(api + "/mongodb");
        assertThat(initial.status()).isEqualTo(200);
        assertThat(initial.json().path("available").asBoolean()).isTrue();
        JsonNode selected = client(initial.json(), clientName, style);
        assertThat(selected.path("lifecycle").asText()).isEqualTo("INITIALIZED");
        assertThat(selected.path("inspectable").asBoolean()).isTrue();
        var inspected = probe.request(
                "POST",
                api + "/mongodb/inspect",
                JSON,
                "{\"clientId\":\"" + selected.path("id").asText() + "\",\"scope\":\"CONFIGURED\"}");
        assertThat(inspected.status()).isEqualTo(200);
        var result = inspected.json();
        assertThat(result.path("inspection").path("status").asText()).isIn("READ", "PARTIAL");
        assertThat(result.path("inspection").path("databasesRetained").asInt()).isPositive();
        assertThat(result.path("inspection").path("collectionsRetained").asInt())
                .isPositive();
        assertThat(result.path("inspection").path("indexesRetained").asInt()).isPositive();
        String snapshot = result.path("inspection").path("snapshotId").asText();
        assertThat(snapshot).isNotBlank();
        var collections = probe.get(api + "/mongodb?snapshotId=" + snapshot + "&section=COLLECTIONS");
        assertThat(collections.status()).isEqualTo(200);
        assertThat(collections.json().path("catalog").path("collections").toString())
                .contains("fixture_orders");
        var indexes = probe.get(api + "/mongodb?snapshotId=" + snapshot + "&section=INDEXES");
        assertThat(indexes.status()).isEqualTo(200);
        assertThat(indexes.json().path("catalog").path("indexes").toString()).contains("fixture_compound");
        assertKeys(indexes.json(), "fixture_compound", List.of("region", "placed"), List.of("ASC", "DESC"));
        assertKeys(indexes.json(), "fixture_hashed", List.of("region"), List.of("HASHED"));
        assertKeys(indexes.json(), "fixture_wildcard", List.of("attributes.$**"), List.of("WILDCARD"));
        assertThat(result.toString() + collections.json() + indexes.json())
                .doesNotContain("mongodb://", "document_secret", "must-never-be-observed", "fixture_root");
        return result;
    }

    private static void assertKeys(JsonNode report, String name, List<String> fields, List<String> kinds) {
        for (JsonNode index : report.path("catalog").path("indexes")) {
            if (!name.equals(index.path("name").asText())) continue;
            List<String> actualFields = new ArrayList<>();
            List<String> actualKinds = new ArrayList<>();
            for (JsonNode key : index.path("keys")) {
                actualFields.add(key.path("field").asText());
                actualKinds.add(key.path("kind").asText());
            }
            assertThat(actualFields).containsExactlyElementsOf(fields);
            assertThat(actualKinds).containsExactlyElementsOf(kinds);
            return;
        }
        throw new AssertionError("Missing observed index " + name);
    }

    static JsonNode client(JsonNode report, String name, String style) {
        for (JsonNode client : report.path("inventory").path("clients")) {
            if (name.equals(client.path("name").asText())
                    && style.equals(client.path("driverStyle").asText())) {
                return client;
            }
        }
        throw new AssertionError("Missing managed " + name + " " + style + " client");
    }
}
