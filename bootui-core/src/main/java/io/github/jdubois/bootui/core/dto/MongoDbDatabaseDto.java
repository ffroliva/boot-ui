package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record MongoDbDatabaseDto(
        String id, String clientId, String name, String provenance, List<MongoDbCapabilityDto> capabilities) {
    public MongoDbDatabaseDto {
        capabilities = DtoCollections.immutableCopy(capabilities);
    }
}
