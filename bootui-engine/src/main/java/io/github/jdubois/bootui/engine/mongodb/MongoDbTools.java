package io.github.jdubois.bootui.engine.mongodb;

import io.github.jdubois.bootui.core.dto.MongoDbReport;
import io.github.jdubois.bootui.engine.mcp.McpArguments;
import io.github.jdubois.bootui.engine.mcp.McpToolClientException;

/** Mechanical, framework-free tool-to-service binding. Transport policies run before this bridge. */
public final class MongoDbTools {
    private MongoDbTools() {}

    public static MongoDbReport report(MongoDbInspectionService service, McpArguments arguments) {
        try {
            var selection = arguments.mongoDb();
            return service.report(
                    selection.get("snapshotId"),
                    selection.get("section"),
                    selection.get("databaseId"),
                    selection.get("collectionId"),
                    arguments.query(),
                    arguments.offset(),
                    arguments.limit());
        } catch (MongoDbRequestException refusal) {
            throw new McpToolClientException(refusal.status(), refusal.getMessage());
        }
    }

    public static MongoDbReport inspect(MongoDbInspectionService service, McpArguments arguments) {
        try {
            return service.inspect(MongoDbRequests.inspect(arguments.mongoDb()));
        } catch (MongoDbRequestException refusal) {
            throw new McpToolClientException(refusal.status(), refusal.getMessage());
        }
    }
}
