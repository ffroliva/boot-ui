package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Detail view of a Spring Data repository, including its query methods.
 */
public record RepositoryDetailDto(
        String beanName,
        String repositoryInterface,
        String domainType,
        String idType,
        String storeModule,
        String customImplementation,
        List<RepositoryMethodDto> methods,
        List<String> fragments,
        String executionKind,
        RepositoryMongoDbDto mongodb) {

    public RepositoryDetailDto(
            String beanName,
            String repositoryInterface,
            String domainType,
            String idType,
            String storeModule,
            String customImplementation,
            List<RepositoryMethodDto> methods,
            List<String> fragments,
            RepositoryMongoDbDto mongodb) {
        this(
                beanName,
                repositoryInterface,
                domainType,
                idType,
                storeModule,
                customImplementation,
                methods,
                fragments,
                null,
                mongodb);
    }

    public RepositoryDetailDto(
            String beanName,
            String repositoryInterface,
            String domainType,
            String idType,
            String storeModule,
            String customImplementation,
            List<RepositoryMethodDto> methods,
            List<String> fragments) {
        this(
                beanName,
                repositoryInterface,
                domainType,
                idType,
                storeModule,
                customImplementation,
                methods,
                fragments,
                null,
                null);
    }

    public RepositoryDetailDto {
        methods = DtoCollections.immutableCopy(methods);
        fragments = DtoCollections.immutableCopy(fragments);
    }
}
