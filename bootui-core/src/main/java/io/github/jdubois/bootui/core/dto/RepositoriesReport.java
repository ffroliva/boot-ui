package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record RepositoriesReport(
        boolean springDataPresent, int total, List<RepositoryDto> repositories, RepositoryDiscoveryDto discovery) {

    public RepositoriesReport(boolean springDataPresent, int total, List<RepositoryDto> repositories) {
        this(springDataPresent, total, repositories, null);
    }

    public RepositoriesReport {
        repositories = DtoCollections.immutableCopy(repositories);
    }
}
