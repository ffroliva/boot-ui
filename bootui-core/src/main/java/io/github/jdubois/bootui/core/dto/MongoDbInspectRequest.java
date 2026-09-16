package io.github.jdubois.bootui.core.dto;

public record MongoDbInspectRequest(
        String clientId, String scope, String databaseId, String collectionId, String snapshotId) {}
