package io.github.jdubois.bootui.autoconfigure.transactions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.target.SingletonTargetSource;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.TransactionExecutionListener;

/**
 * Binds manager-aware bridges without resolving lazy managers or replacing application listeners.
 * Post-processing covers managers created later; the singleton pass covers already-created managers.
 * Declare this infrastructure bean with a static factory and a lazy listener provider.
 */
public final class BootUiTransactionManagerListenerRegistrar
        implements BeanPostProcessor, BeanFactoryAware, SmartInitializingSingleton {

    private final Supplier<? extends TransactionExecutionListener> listener;
    private static final Logger LOG = LoggerFactory.getLogger(BootUiTransactionManagerListenerRegistrar.class);
    private ConfigurableListableBeanFactory beanFactory;

    public BootUiTransactionManagerListenerRegistrar(ObjectProvider<BootUiTransactionExecutionListener> listener) {
        this.listener = listener::getObject;
    }

    public BootUiTransactionManagerListenerRegistrar(
            ObjectProvider<ConfigurableTransactionManager> transactionManagers, TransactionExecutionListener listener) {
        Objects.requireNonNull(transactionManagers);
        this.listener = () -> listener;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        if (!(beanFactory instanceof ConfigurableListableBeanFactory configurable)) {
            throw new IllegalArgumentException(
                    "Transaction listener registration requires a configurable bean factory");
        }
        this.beanFactory = configurable;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof ConfigurableTransactionManager manager
                && !AopUtils.isAopProxy(manager)
                && !java.lang.reflect.Proxy.isProxyClass(manager.getClass())) {
            // Observe the real bean before a later post-processor can wrap it in an opaque proxy.
            registerIfMissing(manager);
        }
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof ConfigurableTransactionManager manager) {
            registerIfMissing(manager);
        }
        return bean;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Objects.requireNonNull(beanFactory, "BeanFactory must be supplied before transaction listener registration");
        for (String name : beanFactory.getSingletonNames()) {
            Object singleton = beanFactory.getSingleton(name);
            if (singleton instanceof ConfigurableTransactionManager manager) {
                registerIfMissing(manager);
            }
        }
    }

    private void registerIfMissing(ConfigurableTransactionManager manager) {
        manager = initializedTarget(manager);
        if (manager == null) {
            LOG.warn(
                    "BootUI cannot bind transaction metadata to an unresolved manager proxy; its target was not initialized.");
            return;
        }
        TransactionExecutionListener source = listener.get();
        List<TransactionExecutionListener> current = new ArrayList<>(manager.getTransactionExecutionListeners());
        if (!(source instanceof BootUiTransactionExecutionListener bootUi)) {
            if (current.stream().noneMatch(candidate -> candidate == source)) {
                manager.addListener(source);
            }
            return;
        }
        ConfigurableTransactionManager target = manager;
        BootUiTransactionExecutionListener bridge = current.stream()
                .filter(candidate -> candidate != bootUi)
                .filter(BootUiTransactionExecutionListener.class::isInstance)
                .map(BootUiTransactionExecutionListener.class::cast)
                .filter(candidate -> candidate.belongsTo(bootUi))
                .findFirst()
                .orElseGet(() -> bootUi.forManager(target));
        List<TransactionExecutionListener> updated = new ArrayList<>();
        boolean added = false;
        for (TransactionExecutionListener candidate : current) {
            if (candidate instanceof BootUiTransactionExecutionListener other && other.belongsTo(bootUi)) {
                if (!added) {
                    updated.add(bridge);
                    added = true;
                }
            } else {
                updated.add(candidate);
            }
        }
        if (!added) {
            updated.add(bridge);
        }
        if (!updated.equals(current)) {
            manager.setTransactionExecutionListeners(updated);
        }
    }

    private static ConfigurableTransactionManager initializedTarget(ConfigurableTransactionManager manager) {
        for (int depth = 0; depth < 8; depth++) {
            if (!AopUtils.isAopProxy(manager) && !java.lang.reflect.Proxy.isProxyClass(manager.getClass())) {
                return manager;
            }
            if (!(manager instanceof Advised advised)
                    || !(advised.getTargetSource() instanceof SingletonTargetSource source)
                    || !(source.getTarget() instanceof ConfigurableTransactionManager target)) {
                return null;
            }
            manager = target;
        }
        return null;
    }
}
