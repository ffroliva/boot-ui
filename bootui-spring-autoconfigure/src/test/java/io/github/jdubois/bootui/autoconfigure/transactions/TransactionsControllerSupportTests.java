package io.github.jdubois.bootui.autoconfigure.transactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.dto.TransactionRecordingRequest;
import io.github.jdubois.bootui.core.dto.TransactionReport;
import io.github.jdubois.bootui.engine.transactions.TransactionRecorder;
import io.github.jdubois.bootui.engine.transactions.TransactionRecorder.Status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.transaction.ConfigurableTransactionManager;

class TransactionsControllerSupportTests {

    @Test
    void traceReportsUnavailableWhenRecorderIsAbsent() {
        TransactionReport report = TransactionsControllerSupport.trace(
                provider(null), provider(mock(ConfigurableTransactionManager.class)));

        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).isEqualTo("Transaction capture is not configured");
    }

    @Test
    void traceReportsUnavailableWhenRecorderIsDisabled() {
        TransactionRecorder recorder = new TransactionRecorder(false, true, 10, 100, 100, null);
        TransactionReport report = TransactionsControllerSupport.trace(
                provider(recorder), provider(mock(ConfigurableTransactionManager.class)));

        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).contains("disabled");
    }

    @Test
    void traceReportsUnavailableWhenNoTransactionManagerBeanExists() {
        TransactionRecorder recorder = new TransactionRecorder(true, true, 10, 100, 100, null);
        TransactionReport report = TransactionsControllerSupport.trace(provider(recorder), provider(null));

        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).isEqualTo("No configurable transaction manager bean is available");
    }

    @Test
    void traceReturnsTheRecorderReportWhenAvailable() {
        TransactionRecorder recorder = new TransactionRecorder(true, true, 10, 100, 100, null);
        long id = recorder.beginTransaction("Service.method", false, "READ_COMMITTED", "main", null);
        recorder.completeTransaction(id, Status.COMMITTED, null);

        TransactionReport report = TransactionsControllerSupport.trace(
                provider(recorder), provider(mock(ConfigurableTransactionManager.class)));

        assertThat(report.available()).isTrue();
        assertThat(report.entries()).hasSize(1);
    }

    @Test
    void clearEmptiesTheBuffer() {
        TransactionRecorder recorder = new TransactionRecorder(true, true, 10, 100, 100, null);
        long id = recorder.beginTransaction("Service.method", false, "READ_COMMITTED", "main", null);
        recorder.completeTransaction(id, Status.COMMITTED, null);
        ObjectProvider<ConfigurableTransactionManager> transactionManagerProvider =
                provider(mock(ConfigurableTransactionManager.class));

        TransactionReport report = TransactionsControllerSupport.clear(provider(recorder), transactionManagerProvider);

        assertThat(report.available()).isTrue();
        assertThat(report.entries()).isEmpty();
    }

    @Test
    void clearReportsUnavailableWhenRecorderIsAbsent() {
        TransactionReport report = TransactionsControllerSupport.clear(
                provider(null), provider(mock(ConfigurableTransactionManager.class)));

        assertThat(report.available()).isFalse();
    }

    @Test
    void recordingTogglesWhenNoExplicitValueIsGiven() {
        TransactionRecorder recorder = new TransactionRecorder(true, true, 10, 100, 100, null);
        ObjectProvider<ConfigurableTransactionManager> transactionManagerProvider =
                provider(mock(ConfigurableTransactionManager.class));

        TransactionsControllerSupport.recording(
                provider(recorder), transactionManagerProvider, new TransactionRecordingRequest(null));
        assertThat(recorder.isRecording()).isFalse();

        TransactionsControllerSupport.recording(
                provider(recorder), transactionManagerProvider, new TransactionRecordingRequest(null));
        assertThat(recorder.isRecording()).isTrue();
    }

    @Test
    void recordingSetsExplicitValueWhenGiven() {
        TransactionRecorder recorder = new TransactionRecorder(true, true, 10, 100, 100, null);
        ObjectProvider<ConfigurableTransactionManager> transactionManagerProvider =
                provider(mock(ConfigurableTransactionManager.class));

        TransactionsControllerSupport.recording(
                provider(recorder), transactionManagerProvider, new TransactionRecordingRequest(false));
        assertThat(recorder.isRecording()).isFalse();
    }

    @Test
    void recordingReportsUnavailableWhenRecorderIsAbsent() {
        TransactionReport report = TransactionsControllerSupport.recording(
                provider(null),
                provider(mock(ConfigurableTransactionManager.class)),
                new TransactionRecordingRequest(true));

        assertThat(report.available()).isFalse();
    }

    @Test
    void beanFactoryOverloadsReportDeclaredReactiveManagersWithoutInitializingThem() {
        var beans = new DefaultListableBeanFactory();
        var lazy =
                new RootBeanDefinition(org.springframework.data.mongodb.ReactiveMongoTransactionManager.class, () -> {
                    throw new AssertionError("A passive transaction report must not instantiate a lazy manager");
                });
        lazy.setLazyInit(true);
        beans.registerBeanDefinition("reactive", lazy);
        var recorder = new TransactionRecorder(true, true, 10, 100, 100, null);
        assertThat(TransactionsControllerSupport.trace(provider(recorder), beans)
                        .available())
                .isTrue();
        assertThat(TransactionsControllerSupport.clear(provider(recorder), beans)
                        .available())
                .isTrue();
        assertThat(TransactionsControllerSupport.recording(
                                provider(recorder), beans, new TransactionRecordingRequest(false))
                        .available())
                .isTrue();
        assertThat(beans.containsSingleton("reactive")).isFalse();
    }

    @Test
    void nonEagerAvailabilityDoesNotInitializeAnUnresolvedFactoryBean() {
        var beans = new DefaultListableBeanFactory();
        var lazy = new RootBeanDefinition(FactoryBean.class, () -> {
            throw new AssertionError("Availability must not instantiate a factory to ask for its object type");
        });
        lazy.setLazyInit(true);
        beans.registerBeanDefinition("unknownFactory", lazy);
        var recorder = new TransactionRecorder(true, true, 10, 100, 100, null);
        TransactionReport report = TransactionsControllerSupport.trace(provider(recorder), beans);
        assertThat(report.available()).isFalse();
        assertThat(beans.containsSingleton("unknownFactory")).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        when(provider.stream())
                .thenAnswer(
                        ignored -> value == null ? java.util.stream.Stream.empty() : java.util.stream.Stream.of(value));
        return provider;
    }
}
