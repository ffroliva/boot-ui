package io.github.jdubois.bootui.core.dto;

import java.util.List;

/** Static declarations only; no operational index comparison or inferred client binding. */
public record RepositoryMongoDbDto(
        String binding,
        String clientId,
        String databaseId,
        String collectionId,
        RepositoryDocumentMappingDto mapping,
        List<RepositoryDeclaredIndexDto> declaredIndexes,
        List<String> limitations) {
    public RepositoryMongoDbDto {
        declaredIndexes = DtoCollections.immutableCopy(declaredIndexes);
        limitations = DtoCollections.immutableCopy(limitations);
    }
}
