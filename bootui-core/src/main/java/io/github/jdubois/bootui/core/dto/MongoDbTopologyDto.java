package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record MongoDbTopologyDto(
        long observedAt, String type, String mode, List<MongoDbServerDto> servers, List<String> limitations) {
    public MongoDbTopologyDto {
        servers = DtoCollections.immutableCopy(servers);
        limitations = DtoCollections.immutableCopy(limitations);
    }
}
