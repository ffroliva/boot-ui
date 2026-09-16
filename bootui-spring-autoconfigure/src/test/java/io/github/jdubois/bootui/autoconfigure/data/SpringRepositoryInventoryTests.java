package io.github.jdubois.bootui.autoconfigure.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.support.RepositoryFactoryInformation;

class SpringRepositoryInventoryTests {
    @Test
    void factoryDereferenceUsesTheExistingSingletonAndDeduplicatesAliases() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        var factory = mock(RepositoryFactoryInformation.class);
        var information = mock(RepositoryInformation.class);
        when(factory.getRepositoryInformation()).thenReturn(information);
        beans.registerSingleton("repositoryFactory", factory);
        beans.registerAlias("repositoryFactory", "alias");
        var discovered = new SpringRepositoryInventory().discover(beans);
        assertThat(discovered.entries()).hasSize(1);
        assertThat(discovered.discovery().complete()).isTrue();
        verify(factory).getRepositoryInformation();
        verifyNoMoreInteractions(factory);
    }

    @Test
    void uninitializedFactoryIsNotCreatedAndFailuresAreVisibleWithoutCauseText() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        RootBeanDefinition definition = new RootBeanDefinition(RepositoryFactoryInformation.class, () -> {
            throw new AssertionError("Lazy repository factory was created");
        });
        definition.setLazyInit(true);
        beans.registerBeanDefinition("lazyRepository", definition);
        var broken = mock(RepositoryFactoryInformation.class);
        when(broken.getRepositoryInformation()).thenThrow(new IllegalStateException("SECRET_SENTINEL"));
        beans.registerSingleton("brokenRepository", broken);
        var discovered = new SpringRepositoryInventory().discover(beans);
        assertThat(discovered.entries()).isEmpty();
        assertThat(discovered.discovery().complete()).isFalse();
        assertThat(discovered.discovery().warnings()).hasSize(2);
        assertThat(discovered.discovery().toString()).doesNotContain("SECRET_SENTINEL");
        assertThat(beans.containsSingleton("lazyRepository")).isFalse();
    }
}
