package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record MongoDbInspectionDto(
        String snapshotId,
        String clientId,
        String scope,
        String status,
        Long startedAt,
        Long completedAt,
        String databaseId,
        String collectionId,
        List<MongoDbSettingDto> serverInformation,
        List<MongoDbCapabilityDto> capabilities,
        int databasesRetained,
        int collectionsRetained,
        int indexesRetained,
        int retainedItems,
        long retainedBytes,
        boolean truncated,
        List<String> limitations) {
    public MongoDbInspectionDto {
        serverInformation = DtoCollections.immutableCopy(serverInformation);
        capabilities = DtoCollections.immutableCopy(capabilities);
        limitations = DtoCollections.immutableCopy(limitations);
    }
}
