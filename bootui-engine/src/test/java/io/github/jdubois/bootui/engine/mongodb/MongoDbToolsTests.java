package io.github.jdubois.bootui.engine.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import io.github.jdubois.bootui.core.dto.MongoDbInspectRequest;
import io.github.jdubois.bootui.core.dto.MongoDbReport;
import io.github.jdubois.bootui.engine.mcp.McpArguments;
import io.github.jdubois.bootui.engine.mcp.McpToolClientException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MongoDbToolsTests {
    @Test
    void reportForwardsEveryRetainedPageArgumentAndReturnsTheSameReport() {
        var service = mock(MongoDbInspectionService.class);
        var report = mock(MongoDbReport.class);
        when(service.report("snapshot", "INDEXES", "database", "collection", "compound", 7, 13))
                .thenReturn(report);
        var arguments = new McpArguments(
                "compound",
                13,
                null,
                null,
                7,
                Map.of(
                        "snapshotId", "snapshot",
                        "section", "INDEXES",
                        "databaseId", "database",
                        "collectionId", "collection"));

        assertThat(MongoDbTools.report(service, arguments)).isSameAs(report);
        verify(service).report("snapshot", "INDEXES", "database", "collection", "compound", 7, 13);
        verifyNoMoreInteractions(service);
    }

    @Test
    void inspectForwardsEverySelectorAndReturnsTheSameReport() {
        var service = mock(MongoDbInspectionService.class);
        var report = mock(MongoDbReport.class);
        var request = new MongoDbInspectRequest("client", "SELECTED", "database", "collection", "snapshot");
        when(service.inspect(request)).thenReturn(report);

        assertThat(MongoDbTools.inspect(service, selectedArguments())).isSameAs(report);
        verify(service).inspect(request);
        verifyNoMoreInteractions(service);
    }

    @Test
    void omittedSelectorsStayAbsentAndInspectionDefaultsToConfigured() {
        var service = mock(MongoDbInspectionService.class);
        MongoDbTools.report(service, new McpArguments(null, null, null));
        MongoDbTools.inspect(service, new McpArguments(null, null, null, null, null, Map.of("clientId", "client")));
        verify(service).report(null, null, null, null, null, null, null);
        verify(service).inspect(new MongoDbInspectRequest("client", "CONFIGURED", null, null, null));
        verifyNoMoreInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 409})
    void reportAndInspectKeepTheCanonicalRefusalStatusAndMessage(int status) {
        var service = mock(MongoDbInspectionService.class);
        var refusal = new MongoDbRequestException(status, "Safe MongoDB refusal");
        when(service.report(null, null, null, null, null, null, null)).thenThrow(refusal);
        when(service.inspect(new MongoDbInspectRequest("client", "SELECTED", "database", "collection", "snapshot")))
                .thenThrow(refusal);

        assertThatThrownBy(() -> MongoDbTools.report(service, new McpArguments(null, null, null)))
                .isInstanceOfSatisfying(McpToolClientException.class, error -> {
                    assertThat(error.status()).isEqualTo(status);
                    assertThat(error.getMessage()).isEqualTo("Safe MongoDB refusal");
                });
        assertThatThrownBy(() -> MongoDbTools.inspect(service, selectedArguments()))
                .isInstanceOfSatisfying(McpToolClientException.class, error -> {
                    assertThat(error.status()).isEqualTo(status);
                    assertThat(error.getMessage()).isEqualTo("Safe MongoDB refusal");
                });
    }

    @Test
    void invalidInspectionFieldsAreRejectedBeforeTheService() {
        var service = mock(MongoDbInspectionService.class);
        var arguments =
                new McpArguments(null, null, null, null, null, Map.of("clientId", "client", "command", "withheld"));
        assertThatThrownBy(() -> MongoDbTools.inspect(service, arguments))
                .isInstanceOfSatisfying(McpToolClientException.class, error -> {
                    assertThat(error.status()).isEqualTo(400);
                    assertThat(error.getMessage()).isEqualTo("Unexpected MongoDB inspection fields");
                });
        verifyNoInteractions(service);
    }

    private static McpArguments selectedArguments() {
        return new McpArguments(
                null,
                null,
                null,
                null,
                null,
                Map.of(
                        "clientId", "client",
                        "scope", "SELECTED",
                        "databaseId", "database",
                        "collectionId", "collection",
                        "snapshotId", "snapshot"));
    }
}
