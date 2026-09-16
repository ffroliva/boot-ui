package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record RepositoryDiscoveryDto(boolean complete, boolean truncated, List<String> warnings) {
    public RepositoryDiscoveryDto {
        warnings = DtoCollections.immutableCopy(warnings);
    }
}
