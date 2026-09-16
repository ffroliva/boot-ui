package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record MongoDbCatalogPageDto(
        String section,
        PageMetadata page,
        List<MongoDbDatabaseDto> databases,
        List<MongoDbCollectionDto> collections,
        List<MongoDbIndexDto> indexes) {
    public MongoDbCatalogPageDto {
        databases = DtoCollections.immutableCopy(databases);
        collections = DtoCollections.immutableCopy(collections);
        indexes = DtoCollections.immutableCopy(indexes);
    }
}
