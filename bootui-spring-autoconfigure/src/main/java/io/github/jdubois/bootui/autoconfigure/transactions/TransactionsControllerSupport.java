package io.github.jdubois.bootui.autoconfigure.transactions;

import io.github.jdubois.bootui.core.dto.TransactionRecordingRequest;
import io.github.jdubois.bootui.core.dto.TransactionReport;
import io.github.jdubois.bootui.engine.transactions.TransactionRecorder;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.ConfigurableTransactionManager;

/**
 * Shared read/clear/recording business logic for the BootUI Transactions panel, used by both the
 * servlet {@code TransactionsController} and the WebFlux {@code ReactiveTransactionsController}.
 * Both bindings expose the identical REST contract over the same framework-neutral
 * {@link TransactionRecorder}, and none of this logic touches a servlet or reactive
 * request/response type, so it is extracted once here rather than duplicated per transport,
 * mirroring {@code SqlTraceControllerSupport}.
 *
 * <p>New adapter wiring must use the {@link ListableBeanFactory} overloads for non-eager declaration
 * lookup. The provider overloads retain the previous calling contract only.</p>
 */
public final class TransactionsControllerSupport {

    private static final String NOT_CONFIGURED = "Transaction capture is not configured";

    private TransactionsControllerSupport() {}

    public static TransactionReport trace(
            ObjectProvider<TransactionRecorder> recorderProvider,
            ObjectProvider<ConfigurableTransactionManager> transactionManagerProvider) {
        return trace(
                recorderProvider,
                () -> transactionManagerProvider.stream().findAny().isPresent());
    }

    public static TransactionReport trace(
            ObjectProvider<TransactionRecorder> recorderProvider, ListableBeanFactory beanFactory) {
        return trace(recorderProvider, () -> hasManager(beanFactory));
    }

    private static TransactionReport trace(
            ObjectProvider<TransactionRecorder> recorderProvider, BooleanSupplier managerAvailable) {
        TransactionRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null) {
            return TransactionReport.unavailable(NOT_CONFIGURED);
        }
        return report(recorder, managerAvailable);
    }

    public static TransactionReport clear(
            ObjectProvider<TransactionRecorder> recorderProvider,
            ObjectProvider<ConfigurableTransactionManager> transactionManagerProvider) {
        return clear(
                recorderProvider,
                () -> transactionManagerProvider.stream().findAny().isPresent());
    }

    public static TransactionReport clear(
            ObjectProvider<TransactionRecorder> recorderProvider, ListableBeanFactory beanFactory) {
        return clear(recorderProvider, () -> hasManager(beanFactory));
    }

    private static TransactionReport clear(
            ObjectProvider<TransactionRecorder> recorderProvider, BooleanSupplier managerAvailable) {
        TransactionRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null) {
            return TransactionReport.unavailable(NOT_CONFIGURED);
        }
        recorder.clear();
        return report(recorder, managerAvailable);
    }

    public static TransactionReport recording(
            ObjectProvider<TransactionRecorder> recorderProvider,
            ObjectProvider<ConfigurableTransactionManager> transactionManagerProvider,
            TransactionRecordingRequest request) {
        return recording(
                recorderProvider,
                () -> transactionManagerProvider.stream().findAny().isPresent(),
                request);
    }

    public static TransactionReport recording(
            ObjectProvider<TransactionRecorder> recorderProvider,
            ListableBeanFactory beanFactory,
            TransactionRecordingRequest request) {
        return recording(recorderProvider, () -> hasManager(beanFactory), request);
    }

    private static TransactionReport recording(
            ObjectProvider<TransactionRecorder> recorderProvider,
            BooleanSupplier managerAvailable,
            TransactionRecordingRequest request) {
        TransactionRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null) {
            return TransactionReport.unavailable(NOT_CONFIGURED);
        }
        boolean enabled = (request == null || request.enabled() == null) ? !recorder.isRecording() : request.enabled();
        recorder.setRecording(enabled);
        return report(recorder, managerAvailable);
    }

    private static boolean hasManager(ListableBeanFactory beanFactory) {
        return beanFactory.getBeanNamesForType(ConfigurableTransactionManager.class, true, false).length > 0;
    }

    private static TransactionReport report(TransactionRecorder recorder, BooleanSupplier managerAvailable) {
        if (!recorder.isEnabled()) {
            return TransactionReport.unavailable(
                    "Transaction capture is disabled (set bootui.transactions.enabled=true in a trusted local"
                            + " profile).");
        }
        if (!managerAvailable.getAsBoolean()) {
            return TransactionReport.unavailable("No configurable transaction manager bean is available");
        }
        return recorder.report();
    }
}
