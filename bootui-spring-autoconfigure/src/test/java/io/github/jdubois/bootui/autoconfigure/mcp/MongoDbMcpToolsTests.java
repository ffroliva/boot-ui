package io.github.jdubois.bootui.autoconfigure.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveBootUiMcpTools;
import io.github.jdubois.bootui.core.dto.MongoDbInspectRequest;
import io.github.jdubois.bootui.core.dto.MongoDbReport;
import io.github.jdubois.bootui.engine.mcp.McpArguments;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolSchema;
import io.github.jdubois.bootui.engine.mongodb.MongoDbInspectionService;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MongoDbMcpToolsTests {
    @Test
    void bothSpringRegistriesInvokeTheSharedReportAndInspectionWithEveryArgument() {
        for (Class<?> registry : List.of(BootUiMcpTools.class, ReactiveBootUiMcpTools.class)) {
            var service = mock(MongoDbInspectionService.class);
            var report = mock(MongoDbReport.class);
            var inspected = mock(MongoDbReport.class);
            var request = new MongoDbInspectRequest("client", "SELECTED", "database", "collection", "snapshot");
            when(service.report("snapshot", "INDEXES", "database", "collection", "compound", 7, 13))
                    .thenReturn(report);
            when(service.inspect(request)).thenReturn(inspected);
            var tools = McpToolsRegistryFixture.maximalRegistry(
                    registry, "tools", Map.of(MongoDbInspectionService.class, service));
            verifyNoInteractions(service);

            var reportTool = tool(tools, "get_mongodb_report");
            assertThat(reportTool.panelId()).isEqualTo(BootUiPanels.MONGODB);
            assertThat(reportTool.schema()).isEqualTo(McpToolSchema.MONGODB_REPORT);
            assertThat(reportTool.action()).isFalse();
            assertThat(reportTool.invoke(new McpArguments(
                            "compound",
                            13,
                            null,
                            null,
                            7,
                            Map.of(
                                    "snapshotId", "snapshot",
                                    "section", "INDEXES",
                                    "databaseId", "database",
                                    "collectionId", "collection"))))
                    .isSameAs(report);

            var inspectTool = tool(tools, "mongodb_inspect");
            assertThat(inspectTool.panelId()).isEqualTo(BootUiPanels.MONGODB);
            assertThat(inspectTool.schema()).isEqualTo(McpToolSchema.MONGODB_INSPECT);
            assertThat(inspectTool.action()).isTrue();
            assertThat(inspectTool.invoke(new McpArguments(
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
                                    "snapshotId", "snapshot"))))
                    .isSameAs(inspected);
            verify(service).report("snapshot", "INDEXES", "database", "collection", "compound", 7, 13);
            verify(service).inspect(request);
            verifyNoMoreInteractions(service);
        }
    }

    private static McpTool tool(List<McpTool> tools, String name) {
        return tools.stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
