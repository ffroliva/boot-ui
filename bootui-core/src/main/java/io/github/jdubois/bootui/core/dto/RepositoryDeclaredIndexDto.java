package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record RepositoryDeclaredIndexDto(
        String name,
        String state,
        List<RepositoryIndexKeyDto> keys,
        Boolean unique,
        Boolean sparse,
        String expireAfterSeconds,
        boolean partialFilterPresent,
        boolean collationPresent,
        boolean wildcardProjectionPresent,
        boolean unsupportedOptions) {
    public RepositoryDeclaredIndexDto {
        keys = DtoCollections.immutableCopy(keys);
    }
}
