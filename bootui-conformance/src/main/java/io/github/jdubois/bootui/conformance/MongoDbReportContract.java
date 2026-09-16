package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Positive real-Mongo contract. Never succeeds by accepting an unavailable panel or empty catalog. */
public final class MongoDbReportContract {
    private static final ObjectMapper JSON = new ObjectMapper();

    private MongoDbReportContract() {}

    public static JsonNode verify(
            BootUiHttpProbe probe,
            String origin,
            String api,
            String clientId,
            String expectedCollection,
            String expectedIndex) {
        var manifest = probe.get(api + "/panels");
        assertThat(manifest.status()).isEqualTo(200);
        assertThat(manifest.json().path("panels")).anySatisfy(panel -> {
            assertThat(panel.path("id").asText()).isEqualTo("mongodb");
            assertThat(panel.path("available").asBoolean()).isTrue();
        });
        var initial = probe.get(api + "/mongodb");
        assertThat(initial.status()).isEqualTo(200);
        assertThat(initial.json().path("available").asBoolean()).isTrue();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", origin);
        headers.put("Content-Type", "application/json");
        probe.cookie("XSRF-TOKEN").ifPresent(token -> headers.put("X-XSRF-TOKEN", token));
        String action = "{\"clientId\":\"" + clientId + "\",\"scope\":\"CONFIGURED\"}";
        for (String query : new String[] {"&unexpected=true", "&section=INDEXES&section=DATABASES"}) {
            assertThat(probe.get(api + "/mongodb?limit=1" + query).status()).isEqualTo(400);
        }
        for (String invalidBody : new String[] {
            "{\"clientId\":\"" + clientId + "\",\"scope\":\"CONFIGURED\",\"scope\":\"AUTHORIZED_NAMES\"}",
            action + "{}",
            "{\"clientId\":\"" + clientId + "\",\"scope\":\"SELECTED\"}"
        }) {
            assertThat(probe.request("POST", api + "/mongodb/inspect", headers, invalidBody)
                            .status())
                    .isEqualTo(400);
        }
        assertThat(probe.request(
                                "POST",
                                api + "/mongodb/inspect",
                                headers,
                                "{\"clientId\":\"" + clientId + "\",\"scope\":\"SELECTED\",\"databaseId\":\"unknown\"}")
                        .status())
                .isEqualTo(404);
        var read = probe.request("POST", api + "/mongodb/inspect", headers, action);
        assertThat(read.status()).as(read.body()).isEqualTo(200);
        assertCatalog(read.json(), probe, api, expectedCollection, expectedIndex);
        JsonNode first = read.json().path("inspection");
        assertThat(probe.get(api + "/mongodb").json().path("inspection")).isEqualTo(first);
        var cli = probe.request("POST", api + "/cli/tools/get_mongodb_report", headers, "{}");
        assertThat(cli.status()).as(cli.body()).isEqualTo(200);
        assertThat(cli.json().path("inspection")).isEqualTo(first);
        assertThat(mcp(probe, api, headers, "get_mongodb_report", "{}").path("inspection"))
                .isEqualTo(first);
        var cliRead = probe.request("POST", api + "/cli/tools/mongodb_inspect", headers, action);
        assertThat(cliRead.status()).as(cliRead.body()).isEqualTo(200);
        assertCatalog(cliRead.json(), probe, api, expectedCollection, expectedIndex);
        JsonNode mcpRead = mcp(probe, api, headers, "mongodb_inspect", action);
        assertCatalog(mcpRead, probe, api, expectedCollection, expectedIndex);
        var blocked = probe.request(
                "POST",
                api + "/mongodb/inspect",
                Map.of(
                        "Origin",
                        "https://foreign.invalid",
                        "Sec-Fetch-Site",
                        "cross-site",
                        "Content-Type",
                        "application/json"),
                action);
        assertThat(blocked.status()).isEqualTo(403);
        assertThat(probe.get(api + "/mongodb").json().path("inspection")).isEqualTo(mcpRead.path("inspection"));
        var invalid = probe.request(
                "POST",
                api + "/cli/tools/mongodb_inspect",
                headers,
                "{\"clientId\":\"" + clientId + "\",\"uri\":\"mongodb://not-allowed\"}");
        assertThat(invalid.status()).isEqualTo(400);
        String snapshotId = mcpRead.path("inspection").path("snapshotId").asText();
        var selected = probe.get(api + "/mongodb?snapshotId=" + snapshotId + "&section=INDEXES&limit=1");
        assertThat(selected.status()).isEqualTo(200);
        var page = selected.json().path("catalog");
        assertThat(page.path("page").path("returned").asInt()).isEqualTo(1);
        var cliPage = probe.request(
                "POST",
                api + "/cli/tools/get_mongodb_report",
                headers,
                "{\"snapshotId\":\"" + snapshotId + "\",\"section\":\"INDEXES\",\"limit\":1}");
        assertThat(cliPage.status()).as(cliPage.body()).isEqualTo(200);
        assertThat(cliPage.json().path("catalog")).isEqualTo(page);
        MongoDbProcessContract.cli(origin, api, clientId);
        return mcpRead;
    }

    public static void assertCatalog(
            JsonNode report, BootUiHttpProbe probe, String api, String expectedCollection, String expectedIndex) {
        assertThat(report.path("localOnly").asBoolean()).isTrue();
        assertThat(report.path("status").asText()).isIn("READ", "PARTIAL");
        JsonNode inspection = report.path("inspection");
        assertThat(inspection.path("snapshotId").asText()).isNotBlank();
        assertThat(inspection.path("databasesRetained").asInt()).isPositive();
        assertThat(inspection.path("collectionsRetained").asInt()).isPositive();
        assertThat(inspection.path("indexesRetained").asInt()).isPositive();
        String snapshot = inspection.path("snapshotId").asText();
        var collections = probe.get(api + "/mongodb?snapshotId=" + snapshot + "&section=COLLECTIONS&limit=200");
        assertThat(collections.status()).as(collections.body()).isEqualTo(200);
        assertThat(collections.json().path("catalog").path("collections"))
                .anyMatch(row -> expectedCollection.equals(row.path("name").asText()));
        var indexes = probe.get(api + "/mongodb?snapshotId=" + snapshot + "&section=INDEXES&limit=200");
        assertThat(indexes.status()).as(indexes.body()).isEqualTo(200);
        assertThat(indexes.json().path("catalog").path("indexes"))
                .filteredOn(row -> expectedIndex.equals(row.path("name").asText()))
                .singleElement()
                .satisfies(index -> assertThat(index.path("keys"))
                        .extracting(key -> key.path("kind").asText())
                        .containsExactly("ASC", "DESC"));
        assertThat(indexes.json().path("catalog").path("indexes"))
                .anySatisfy(index -> assertThat(index.path("keys"))
                        .extracting(key -> key.path("kind").asText())
                        .containsExactly("HASHED"))
                .anySatisfy(index -> assertThat(index.path("keys"))
                        .extracting(key -> key.path("kind").asText())
                        .containsExactly("WILDCARD"));
        assertThat(report.toString() + collections.json() + indexes.json())
                .doesNotContain(
                        "mongodb://",
                        "document_secret",
                        "must-never-be-observed",
                        "partialFilterExpression",
                        "\"pipeline\"",
                        "\"validator\"",
                        "fixtureAdmin",
                        "fixture_root");
    }

    private static JsonNode mcp(
            BootUiHttpProbe probe, String api, Map<String, String> headers, String name, String arguments) {
        var response = probe.request(
                "POST",
                api + "/mcp",
                headers,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + name
                        + "\",\"arguments\":" + arguments + "}}");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().path("result").path("isError").asBoolean())
                .as(response.body())
                .isFalse();
        try {
            return JSON.readTree(response.json()
                    .path("result")
                    .path("content")
                    .get(0)
                    .path("text")
                    .asText());
        } catch (IOException e) {
            throw new AssertionError("MongoDB MCP must return valid JSON", e);
        }
    }
}
