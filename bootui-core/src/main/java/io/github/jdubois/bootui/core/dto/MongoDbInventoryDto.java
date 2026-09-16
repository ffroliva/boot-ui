package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record MongoDbInventoryDto(
        long observedAt,
        List<MongoDbClientDto> clients,
        boolean complete,
        boolean truncated,
        List<String> limitations) {
    public MongoDbInventoryDto {
        clients = DtoCollections.immutableCopy(clients);
        limitations = DtoCollections.immutableCopy(limitations);
    }
}
