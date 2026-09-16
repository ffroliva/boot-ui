package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record MongoDbReport(
        boolean localOnly,
        boolean available,
        String unavailableReason,
        String status,
        String message,
        String disclaimer,
        String valueExposure,
        MongoDbInventoryDto inventory,
        MongoDbInspectionDto inspection,
        MongoDbCatalogPageDto catalog,
        MongoDbLimitsDto limits,
        List<MongoDbDiagnosticDto> diagnostics) {
    public MongoDbReport {
        diagnostics = DtoCollections.immutableCopy(diagnostics);
    }
}
