package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record RepositoryDocumentMappingDto(
        String collection,
        String collectionState,
        String idField,
        String versionField,
        List<RepositoryMappedFieldDto> fields,
        boolean complete) {
    public RepositoryDocumentMappingDto {
        fields = DtoCollections.immutableCopy(fields);
    }
}
