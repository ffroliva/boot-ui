package io.github.jdubois.bootui.engine.mongodb;

import io.github.jdubois.bootui.core.dto.MongoDbInspectRequest;
import java.util.Map;
import java.util.Set;

public final class MongoDbRequests {
    public static final Set<String> SELECTION_FIELDS =
            Set.of("clientId", "scope", "databaseId", "collectionId", "snapshotId", "section");
    private static final Set<String> INSPECT = Set.of("clientId", "scope", "databaseId", "collectionId", "snapshotId");
    private static final Set<String> REPORT =
            Set.of("snapshotId", "section", "databaseId", "collectionId", "query", "offset", "limit");

    private MongoDbRequests() {}

    public static void reportFields(Set<String> fields) {
        if (!REPORT.containsAll(fields)) {
            throw invalid("Unexpected MongoDB report fields");
        }
    }

    public static void reportQuery(Map<String, ? extends java.util.List<String>> parameters) {
        reportFields(parameters.keySet());
        if (parameters.values().stream().anyMatch(values -> values == null || values.size() != 1)) {
            throw invalid("Duplicate MongoDB report fields");
        }
    }

    public static MongoDbInspectRequest inspect(Map<String, ?> input) {
        if (input == null || !INSPECT.containsAll(input.keySet())) {
            throw invalid("Unexpected MongoDB inspection fields");
        }
        return validate(new MongoDbInspectRequest(
                string(input, "clientId"),
                string(input, "scope"),
                string(input, "databaseId"),
                string(input, "collectionId"),
                string(input, "snapshotId")));
    }

    public static MongoDbInspectRequest validate(MongoDbInspectRequest request) {
        if (request == null || request.clientId() == null) throw invalid("clientId is required");
        identifier(request.clientId());
        identifier(request.databaseId());
        identifier(request.collectionId());
        identifier(request.snapshotId());
        String scope = request.scope() == null ? "CONFIGURED" : request.scope();
        if (!Set.of("CONFIGURED", "SELECTED", "AUTHORIZED_NAMES").contains(scope)) {
            throw invalid("Invalid MongoDB scope");
        }
        if ("SELECTED".equals(scope)) {
            if (request.databaseId() == null) throw invalid("SELECTED requires databaseId");
        } else if (request.databaseId() != null || request.collectionId() != null || request.snapshotId() != null) {
            throw invalid("Target selectors are only valid for SELECTED scope");
        }
        return new MongoDbInspectRequest(
                request.clientId(), scope, request.databaseId(), request.collectionId(), request.snapshotId());
    }

    public static String string(Map<String, ?> input, String name) {
        if (!input.containsKey(name)) return null;
        Object value = input.get(name);
        if (!(value instanceof String text) || text.isBlank() || text.length() > 256) {
            throw invalid("Invalid MongoDB " + name);
        }
        return text;
    }

    public static void identifier(String value) {
        if (value != null && !value.matches("[A-Za-z0-9_-]{1,128}")) {
            throw invalid("Invalid MongoDB identifier");
        }
    }

    public static MongoDbRequestException invalid(String message) {
        return new MongoDbRequestException(400, message);
    }
}
