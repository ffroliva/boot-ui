package io.github.jdubois.bootui.core.dto;

public record MongoDbLimitsDto(
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
        boolean authorizedDatabaseEnumerationEnabled) {}
