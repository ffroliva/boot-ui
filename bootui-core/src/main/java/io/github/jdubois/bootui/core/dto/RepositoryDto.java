package io.github.jdubois.bootui.core.dto;

/**
 * Summary of one Spring Data repository discovered in the context.
 */
public record RepositoryDto(
        String beanName,
        String repositoryInterface,
        String domainType,
        String idType,
        String storeModule,
        String customImplementation,
        int queryMethodCount,
        int fragmentCount,
        String executionKind,
        RepositoryMongoDbDto mongodb) {
    public RepositoryDto(
            String beanName,
            String repositoryInterface,
            String domainType,
            String idType,
            String storeModule,
            String customImplementation,
            int queryMethodCount,
            int fragmentCount) {
        this(
                beanName,
                repositoryInterface,
                domainType,
                idType,
                storeModule,
                customImplementation,
                queryMethodCount,
                fragmentCount,
                null,
                null);
    }
}
