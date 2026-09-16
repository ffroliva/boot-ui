package io.github.jdubois.bootui.core.dto;

/**
 * One query method on a Spring Data repository.
 */
public record RepositoryMethodDto(
        String name,
        String signature,
        String origin,
        String query,
        boolean nativeQuery,
        String namedQuery,
        RepositoryQueryMetadataDto queryMetadata) {
    public RepositoryMethodDto(
            String name, String signature, String origin, String query, boolean nativeQuery, String namedQuery) {
        this(name, signature, origin, query, nativeQuery, namedQuery, null);
    }
}
