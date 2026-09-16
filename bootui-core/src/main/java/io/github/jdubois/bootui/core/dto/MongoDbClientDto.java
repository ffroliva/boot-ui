package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record MongoDbClientDto(
        String id,
        String name,
        String driverStyle,
        String driverVersion,
        String lifecycle,
        boolean inspectable,
        List<MongoDbDatabaseDto> configuredDatabases,
        List<MongoDbSettingDto> settings,
        MongoDbTopologyDto topology,
        List<String> limitations) {
    public MongoDbClientDto {
        configuredDatabases = DtoCollections.immutableCopy(configuredDatabases);
        settings = DtoCollections.immutableCopy(settings);
        limitations = DtoCollections.immutableCopy(limitations);
    }
}
