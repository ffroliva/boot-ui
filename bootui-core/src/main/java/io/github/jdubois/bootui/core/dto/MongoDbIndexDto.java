package io.github.jdubois.bootui.core.dto;

import java.util.List;

/** Conventional index declaration observed on the server; no usage or performance verdict. */
public record MongoDbIndexDto(
        String id,
        String collectionId,
        String databaseId,
        String clientId,
        String name,
        List<MongoDbIndexKeyDto> keys,
        Boolean unique,
        Boolean sparse,
        Boolean hidden,
        String expireAfterSeconds,
        boolean partialFilterPresent,
        boolean collationPresent,
        boolean wildcardProjectionPresent,
        boolean hasUnsupportedOptions,
        List<String> limitations) {
    public MongoDbIndexDto {
        keys = DtoCollections.immutableCopy(keys);
        limitations = DtoCollections.immutableCopy(limitations);
    }
}
