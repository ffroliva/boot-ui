package io.github.jdubois.bootui.autoconfigure.transactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.transactions.TransactionRecorder;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.data.mongodb.ReactiveMongoDatabaseFactory;
import org.springframework.data.mongodb.ReactiveMongoTransactionManager;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;

class BootUiTransactionManagerListenerRegistrarTests {

    @Test
    void registersListenerOnUserDefinedManager() {
        RecordingTransactionManager manager = new RecordingTransactionManager();
        TransactionExecutionListener listener = mock(TransactionExecutionListener.class);

        registrar(manager, listener).afterSingletonsInstantiated();

        assertThat(manager.getTransactionExecutionListeners()).containsExactly(listener);
    }

    @Test
    void doesNotDuplicateListenerAlreadyAppliedBySpringBoot() {
        RecordingTransactionManager manager = new RecordingTransactionManager();
        TransactionExecutionListener listener = mock(TransactionExecutionListener.class);
        manager.addListener(listener);

        registrar(manager, listener).afterSingletonsInstantiated();

        assertThat(manager.getTransactionExecutionListeners()).containsExactly(listener);
    }

    @Test
    void managerAwareBridgeReplacesOnlyItsOwnBootListenerAndPreservesCompositionAndOrder() {
        TransactionRecorder recorder = new TransactionRecorder(true, true, 10, 100, 100, null);
        var listener = new BootUiTransactionExecutionListener(recorder);
        var manager = new ReactiveMongoTransactionManager(mock(ReactiveMongoDatabaseFactory.class));
        TransactionExecutionListener before = mock(TransactionExecutionListener.class);
        TransactionExecutionListener after = mock(TransactionExecutionListener.class);
        manager.addListener(before);
        manager.addListener(listener);
        manager.addListener(after);
        var registrar = registrar(manager, listener);
        registrar.afterSingletonsInstantiated();
        var registered = manager.getTransactionExecutionListeners().stream().toList();
        assertThat(registered).hasSize(3);
        assertThat(registered.get(0)).isSameAs(before);
        assertThat(registered.get(1))
                .isInstanceOf(BootUiTransactionExecutionListener.class)
                .isNotSameAs(listener);
        assertThat(registered.get(2)).isSameAs(after);
        registrar.afterSingletonsInstantiated();
        registrar.postProcessAfterInitialization(manager, "manager");
        assertThat(manager.getTransactionExecutionListeners()).containsExactlyElementsOf(registered);
        manager.addListener(listener);
        registrar.afterSingletonsInstantiated();
        assertThat(manager.getTransactionExecutionListeners()).containsExactlyElementsOf(registered);
        var execution = mock(org.springframework.transaction.TransactionExecution.class);
        registered.get(1).afterBegin(execution, null);
        registered.get(1).afterCommit(execution, null);
        assertThat(recorder.recent().get(0).managerType()).isEqualTo(ReactiveMongoTransactionManager.class.getName());
        assertThat(recorder.recent().get(0).executionKind()).isEqualTo("REACTIVE");
    }

    @Test
    void singletonPassDoesNotInstantiateLazyManagersOrFactoriesAndLaterCreationIsObserved() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        AtomicInteger managersCreated = new AtomicInteger();
        AtomicInteger factoriesCreated = new AtomicInteger();
        var listener = new BootUiTransactionExecutionListener(new TransactionRecorder(true, true, 10, 100, 100, null));
        beans.registerSingleton("listener", listener);
        RootBeanDefinition lazyManager = new RootBeanDefinition(RecordingTransactionManager.class, () -> {
            managersCreated.incrementAndGet();
            return new RecordingTransactionManager();
        });
        lazyManager.setLazyInit(true);
        beans.registerBeanDefinition("lazyManager", lazyManager);
        RootBeanDefinition lazyFactory = new RootBeanDefinition(ManagerFactory.class, () -> {
            factoriesCreated.incrementAndGet();
            return new ManagerFactory();
        });
        lazyFactory.setLazyInit(true);
        beans.registerBeanDefinition("factoryManager", lazyFactory);
        var registrar = new BootUiTransactionManagerListenerRegistrar(
                beans.getBeanProvider(BootUiTransactionExecutionListener.class));
        registrar.setBeanFactory(beans);
        beans.addBeanPostProcessor(registrar);

        registrar.afterSingletonsInstantiated();
        assertThat(managersCreated).hasValue(0);
        assertThat(factoriesCreated).hasValue(0);
        var manager = beans.getBean("lazyManager", ConfigurableTransactionManager.class);
        var factoryManager = beans.getBean("factoryManager", ConfigurableTransactionManager.class);
        assertThat(managersCreated).hasValue(1);
        assertThat(factoriesCreated).hasValue(1);
        assertThat(manager.getTransactionExecutionListeners()).hasSize(1);
        assertThat(factoryManager.getTransactionExecutionListeners()).hasSize(1);
        registrar.afterSingletonsInstantiated();
        assertThat(manager.getTransactionExecutionListeners()).hasSize(1);
    }

    @Test
    void doesNotResolveDynamicProxyTargetsButCanRegisterAnExistingSingletonTarget() throws Exception {
        TargetSource targetSource = mock(TargetSource.class);
        when(targetSource.getTargetClass()).thenAnswer(invocation -> RecordingTransactionManager.class);
        when(targetSource.getTarget()).thenThrow(new AssertionError("Lazy target must not be resolved"));
        ProxyFactory lazy = new ProxyFactory();
        lazy.setInterfaces(ConfigurableTransactionManager.class);
        lazy.setTargetSource(targetSource);
        var lazyProxy = (ConfigurableTransactionManager) lazy.getProxy();
        var listener = new BootUiTransactionExecutionListener(new TransactionRecorder(true, true, 10, 100, 100, null));
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("listener", listener);
        beans.registerSingleton("lazy", lazyProxy);
        var manager = new RecordingTransactionManager();
        ProxyFactory initialized = new ProxyFactory(manager);
        beans.registerSingleton("initialized", initialized.getProxy());
        var registrar = new BootUiTransactionManagerListenerRegistrar(
                beans.getBeanProvider(BootUiTransactionExecutionListener.class));
        registrar.setBeanFactory(beans);
        registrar.afterSingletonsInstantiated();
        verify(targetSource, never()).getTarget();
        assertThat(manager.getTransactionExecutionListeners()).hasSize(1);
    }

    @Test
    void opaqueLazyProxyRemainsColdUntilApplicationUseAndItsManagedTargetIsCaptured() {
        AtomicInteger created = new AtomicInteger();
        var recorder = new TransactionRecorder(true, true, 10, 100, 100, null);
        try (var context = new org.springframework.context.support.GenericApplicationContext()) {
            context.registerBean(
                    "listener",
                    BootUiTransactionExecutionListener.class,
                    () -> new BootUiTransactionExecutionListener(recorder));
            context.registerBean(
                    "registrar",
                    BootUiTransactionManagerListenerRegistrar.class,
                    () -> new BootUiTransactionManagerListenerRegistrar(
                            context.getBeanProvider(BootUiTransactionExecutionListener.class)));
            var target = new RootBeanDefinition(RecordingTransactionManager.class, () -> {
                created.incrementAndGet();
                return new RecordingTransactionManager();
            });
            target.setLazyInit(true);
            context.registerBeanDefinition("lazyTarget", target);
            var source = new org.springframework.aop.target.LazyInitTargetSource();
            source.setTargetBeanName("lazyTarget");
            source.setBeanFactory(context.getDefaultListableBeanFactory());
            source.setTargetClass(RecordingTransactionManager.class);
            var factory = new ProxyFactory();
            factory.setInterfaces(
                    org.springframework.transaction.PlatformTransactionManager.class,
                    ConfigurableTransactionManager.class);
            factory.setTargetSource(source);
            factory.setOpaque(true);
            var proxy = (org.springframework.transaction.PlatformTransactionManager) factory.getProxy();
            context.registerBean(
                    "proxy", org.springframework.transaction.PlatformTransactionManager.class, () -> proxy);
            context.refresh();
            assertThat(created).hasValue(0);
            assertThat(recorder.recent()).isEmpty();
            new org.springframework.transaction.support.TransactionTemplate(proxy).execute(status -> null);
            assertThat(created).hasValue(1);
            assertThat(recorder.recent()).singleElement().satisfies(row -> {
                assertThat(row.managerType()).isEqualTo(RecordingTransactionManager.class.getName());
                assertThat(row.executionKind()).isEqualTo("IMPERATIVE");
                assertThat(row.status()).isEqualTo("COMMITTED");
            });
            var actual =
                    (ConfigurableTransactionManager) context.getBeanFactory().getSingleton("lazyTarget");
            assertThat(actual.getTransactionExecutionListeners()).hasSize(1);
        }
    }

    @SuppressWarnings("unchecked")
    private static BootUiTransactionManagerListenerRegistrar registrar(
            ConfigurableTransactionManager manager, TransactionExecutionListener listener) {
        ObjectProvider<ConfigurableTransactionManager> provider = mock(ObjectProvider.class);
        var registrar = new BootUiTransactionManagerListenerRegistrar(provider, listener);
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("manager", manager);
        registrar.setBeanFactory(beans);
        registrar.afterSingletonsInstantiated();
        verifyNoInteractions(provider);
        return registrar;
    }

    private static final class ManagerFactory implements FactoryBean<ConfigurableTransactionManager> {
        @Override
        public ConfigurableTransactionManager getObject() {
            return new RecordingTransactionManager();
        }

        @Override
        public Class<?> getObjectType() {
            return RecordingTransactionManager.class;
        }
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {}

        @Override
        protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {}
    }
}
