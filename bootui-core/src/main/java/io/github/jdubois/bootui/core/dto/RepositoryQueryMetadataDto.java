package io.github.jdubois.bootui.core.dto;

/** Presence and declaration kind, never Mongo query, aggregation, sort or projection literals. */
public record RepositoryQueryMetadataDto(
        String language,
        String kind,
        boolean declared,
        boolean dynamic,
        Integer pipelineStages,
        boolean projectionPresent,
        boolean sortPresent,
        boolean collationPresent,
        boolean textWithheld) {}
