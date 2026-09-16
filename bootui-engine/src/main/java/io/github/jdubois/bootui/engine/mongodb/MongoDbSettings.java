package io.github.jdubois.bootui.engine.mongodb;

import io.github.jdubois.bootui.core.dto.MongoDbLimitsDto;
import java.util.function.Function;

/** Shared startup-bound settings parsing; absent differs from explicitly blank/invalid. */
public record MongoDbSettings(
        int maxClients,
        int maxDatabases,
        int maxCollectionsPerDatabase,
        int maxIndexesPerCollection,
        int maxTotalItems,
        int maxMetadataBytes,
        int maxTextLength,
        int timeoutMillis,
        int operationTimeoutMillis,
        boolean inspectEnabled,
        boolean authorizedDatabaseEnumerationEnabled) {
    public MongoDbSettings {
        check("max-clients", maxClients, 64);
        check("max-databases", maxDatabases, 32);
        check("max-collections-per-database", maxCollectionsPerDatabase, 200);
        check("max-indexes-per-collection", maxIndexesPerCollection, 128);
        check("max-total-items", maxTotalItems, 5000);
        check("max-metadata-bytes", maxMetadataBytes, 2097152);
        if (maxMetadataBytes < 16384) {
            throw new IllegalArgumentException("bootui.mongodb.max-metadata-bytes must be at least 16384");
        }
        check("max-text-length", maxTextLength, 1024);
        check("timeout-ms", timeoutMillis, 30000);
        check("operation-timeout-ms", operationTimeoutMillis, timeoutMillis);
    }

    public static MongoDbSettings defaults() {
        return from(key -> null);
    }

    public static MongoDbSettings from(Function<String, String> properties) {
        return new MongoDbSettings(
                integer(properties, "max-clients", 16),
                integer(properties, "max-databases", 8),
                integer(properties, "max-collections-per-database", 50),
                integer(properties, "max-indexes-per-collection", 32),
                integer(properties, "max-total-items", 1000),
                integer(properties, "max-metadata-bytes", 524288),
                integer(properties, "max-text-length", 256),
                integer(properties, "timeout-ms", 10000),
                integer(properties, "operation-timeout-ms", 2000),
                bool(properties, "inspect-enabled", true),
                bool(properties, "authorized-database-enumeration-enabled", false));
    }

    private static int integer(Function<String, String> properties, String key, int fallback) {
        String raw = properties.apply("bootui.mongodb." + key);
        try {
            return raw == null ? fallback : Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid bootui.mongodb." + key);
        }
    }

    private static boolean bool(Function<String, String> properties, String key, boolean fallback) {
        String raw = properties.apply("bootui.mongodb." + key);
        if (raw == null) return fallback;
        if ("true".equalsIgnoreCase(raw)) return true;
        if ("false".equalsIgnoreCase(raw)) return false;
        throw new IllegalArgumentException("Invalid bootui.mongodb." + key);
    }

    private static void check(String key, int value, int max) {
        if (value < 1 || value > max) {
            throw new IllegalArgumentException("bootui.mongodb." + key + " must be between 1 and " + max);
        }
    }

    public MongoDbLimitsDto dto() {
        return new MongoDbLimitsDto(
                maxClients,
                maxDatabases,
                maxCollectionsPerDatabase,
                maxIndexesPerCollection,
                maxTotalItems,
                maxMetadataBytes,
                maxTextLength,
                timeoutMillis,
                operationTimeoutMillis,
                inspectEnabled,
                authorizedDatabaseEnumerationEnabled);
    }
}
