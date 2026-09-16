package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record MongoDbCollectionDto(
        String id,
        String databaseId,
        String clientId,
        String name,
        String type,
        Boolean capped,
        String cappedSizeBytes,
        String cappedMaxDocuments,
        boolean validatorPresent,
        boolean collationPresent,
        boolean encryptedFieldsPresent,
        List<MongoDbCapabilityDto> capabilities,
        List<String> limitations) {
    public MongoDbCollectionDto {
        capabilities = DtoCollections.immutableCopy(capabilities);
        limitations = DtoCollections.immutableCopy(limitations);
    }
}
