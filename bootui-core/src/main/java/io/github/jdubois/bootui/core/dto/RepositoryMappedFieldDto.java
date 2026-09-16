package io.github.jdubois.bootui.core.dto;

public record RepositoryMappedFieldDto(
        String property, String persistedName, String javaType, String referenceKind, String state) {}
