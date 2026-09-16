package io.github.jdubois.bootui.autoconfigure.transactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.dto.TransactionEntryDto;
import io.github.jdubois.bootui.engine.transactions.TransactionRecorder;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.ReactiveMongoDatabaseFactory;
import org.springframework.data.mongodb.ReactiveMongoTransactionManager;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class BootUiTransactionExecutionListenerTests {

    @AfterEach
    void resetSynchronizationState() {
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
        MDC.clear();
    }

    @Test
    void recordsACommittedTransactionWithIsolationAndReadOnlyFlag() {
        TransactionRecorder recorder = recorder();
        BootUiTransactionExecutionListener listener = jdbcListener(recorder);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);

        TransactionExecution execution = execution("OrderService.placeOrder", false);
        listener.afterBegin(execution, null);
        listener.afterCommit(execution, null);

        TransactionEntryDto entry = recorder.recent().get(0);
        assertThat(entry.methodName()).isEqualTo("OrderService.placeOrder");
        assertThat(entry.isolation()).isEqualTo("READ_COMMITTED");
        assertThat(entry.readOnly()).isFalse();
        assertThat(entry.status()).isEqualTo("COMMITTED");
    }

    @Test
    void recordsUnknownOutcomeWhenRollbackFails() {
        TransactionRecorder recorder = recorder();
        BootUiTransactionExecutionListener listener = jdbcListener(recorder);

        TransactionExecution execution = execution("Service.method", false);
        listener.afterBegin(execution, null);
        listener.afterRollback(execution, new IllegalStateException("constraint violated"));

        TransactionEntryDto entry = recorder.recent().get(0);
        assertThat(entry.status()).isEqualTo("UNKNOWN");
        assertThat(entry.errorMessage()).isEqualTo("constraint violated");
    }

    @Test
    void recordsUnknownStatusWhenBeginFails() {
        TransactionRecorder recorder = recorder();
        BootUiTransactionExecutionListener listener = jdbcListener(recorder);

        TransactionExecution execution = execution("Service.method", false);
        listener.afterBegin(execution, new IllegalStateException("could not open connection"));

        TransactionEntryDto entry = recorder.recent().get(0);
        assertThat(entry.status()).isEqualTo("UNKNOWN");
        assertThat(entry.errorMessage()).isEqualTo("could not open connection");
    }

    @Test
    void recordsUnknownStatusWhenCommitFails() {
        TransactionRecorder recorder = recorder();
        BootUiTransactionExecutionListener listener = jdbcListener(recorder);

        TransactionExecution execution = execution("Service.method", false);
        listener.afterBegin(execution, null);
        listener.afterCommit(execution, new IllegalStateException("commit failed"));

        TransactionEntryDto entry = recorder.recent().get(0);
        assertThat(entry.status()).isEqualTo("UNKNOWN");
        assertThat(entry.errorMessage()).isEqualTo("commit failed");
    }

    @Test
    void tracksNestingAcrossSequentialBeginsOnTheSameThread() {
        TransactionRecorder recorder = recorder();
        BootUiTransactionExecutionListener listener = jdbcListener(recorder);

        TransactionExecution outer = execution("Outer.method", false);
        TransactionExecution inner = execution("Inner.method", false);
        listener.afterBegin(outer, null);
        listener.afterBegin(inner, null);
        listener.afterCommit(inner, null);
        listener.afterCommit(outer, null);

        List<TransactionEntryDto> entries = recorder.recent();
        TransactionEntryDto innerEntry = entries.stream()
                .filter(e -> e.methodName().equals("Inner.method"))
                .findFirst()
                .orElseThrow();
        TransactionEntryDto outerEntry = entries.stream()
                .filter(e -> e.methodName().equals("Outer.method"))
                .findFirst()
                .orElseThrow();
        assertThat(innerEntry.parentId()).isEqualTo(outerEntry.id());
    }

    @Test
    void usesUnknownNameWhenTransactionNameIsBlank() {
        TransactionRecorder recorder = recorder();
        BootUiTransactionExecutionListener listener = jdbcListener(recorder);

        TransactionExecution execution = execution("  ", false);
        listener.afterBegin(execution, null);
        listener.afterCommit(execution, null);

        assertThat(recorder.recent().get(0).methodName()).isEqualTo("unknown");
    }

    @Test
    void readsTraceIdFromMdcWhenPresent() {
        TransactionRecorder recorder = recorder();
        BootUiTransactionExecutionListener listener = jdbcListener(recorder);
        MDC.put("traceId", "abc123");

        TransactionExecution execution = execution("Service.method", false);
        listener.afterBegin(execution, null);
        listener.afterCommit(execution, null);

        assertThat(recorder.recent().get(0).traceId()).isEqualTo("abc123");
    }

    @Test
    void failsOpenWhenRecorderThrowsOnBegin() {
        TransactionRecorder recorder = mock(TransactionRecorder.class);
        when(recorder.beginTransaction(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("boom"));
        BootUiTransactionExecutionListener listener = jdbcListener(recorder);

        TransactionExecution execution = execution("Service.method", false);
        org.assertj.core.api.Assertions.assertThatCode(() -> listener.afterBegin(execution, null))
                .doesNotThrowAnyException();
    }

    @Test
    void completeIsANoOpWhenNoMatchingBeginWasRecorded() {
        TransactionRecorder recorder = recorder();
        BootUiTransactionExecutionListener listener = jdbcListener(recorder);

        TransactionExecution execution = execution("Service.method", false);
        listener.afterCommit(execution, null);

        assertThat(recorder.recent()).isEmpty();
    }

    @Test
    void mongoCallbacksDoNotReadUnrelatedJdbcSynchronizationOrMdc() {
        TransactionRecorder recorder = recorder();
        BootUiTransactionExecutionListener listener = new BootUiTransactionExecutionListener(recorder)
                .forManager(new MongoTransactionManager(mock(MongoDatabaseFactory.class)));
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_SERIALIZABLE);
        MDC.put("traceId", "unrelated-jdbc-trace");
        TransactionExecution execution = execution("mongo", true);
        when(execution.isNewTransaction()).thenReturn(true);
        listener.afterBegin(execution, null);
        listener.afterCommit(execution, null);
        assertThat(recorder.recent()).singleElement().satisfies(entry -> {
            assertThat(entry.managerType()).isEqualTo(MongoTransactionManager.class.getName());
            assertThat(entry.executionKind()).isEqualTo("IMPERATIVE");
            assertThat(entry.correlationStatus()).isEqualTo("NOT_APPLICABLE");
            assertThat(entry.propagation()).isEqualTo("NEW");
            assertThat(entry.readOnly()).isTrue();
            assertThat(entry.isolation()).isEqualTo("UNKNOWN");
            assertThat(entry.traceId()).isNull();
            assertThat(entry.parentId()).isNull();
            assertThat(entry.connectionHeld()).isFalse();
        });
    }

    @Test
    void reactiveCallbacksCompleteByIdentityAcrossThreadHopsAndConcurrentNonLifoCompletion() throws Exception {
        TransactionRecorder recorder = new TransactionRecorder(true, true, 32, 100, 100, null);
        BootUiTransactionExecutionListener listener = reactiveListener(recorder);
        List<TransactionExecution> executions = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            TransactionExecution execution = execution("mongo-" + index, false);
            executions.add(execution);
            listener.afterBegin(execution, null);
        }
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Future<?>> completions = new ArrayList<>();
            for (int index = 0; index < executions.size(); index++) {
                int selected = index;
                completions.add(executor.submit(() -> {
                    if (selected % 2 == 0) {
                        listener.afterCommit(executions.get(selected), null);
                    } else {
                        listener.afterRollback(executions.get(selected), null);
                    }
                }));
            }
            for (var completion : completions) {
                completion.get(5, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(recorder.recent()).hasSize(12).allSatisfy(entry -> {
            int index = Integer.parseInt(entry.methodName().substring("mongo-".length()));
            assertThat(entry.status()).isEqualTo(index % 2 == 0 ? "COMMITTED" : "ROLLED_BACK");
            assertThat(entry.parentId()).isNull();
            assertThat(entry.traceId()).isNull();
            assertThat(entry.executionKind()).isEqualTo("REACTIVE");
            assertThat(entry.managerType()).isEqualTo(ReactiveMongoTransactionManager.class.getName());
        });
        TransactionExecution next = execution("next", false);
        listener.afterBegin(next, null);
        listener.afterCommit(next, null);
        assertThat(recorder.recent().get(0).parentId()).isNull();
    }

    @Test
    void reactiveFailuresAndUnmatchedCallbacksDoNotConsumeAnotherExecutionOrExposeDriverErrors() {
        TransactionRecorder recorder = recorder();
        BootUiTransactionExecutionListener listener = reactiveListener(recorder);
        TransactionExecution outer = execution("outer", false);
        TransactionExecution failedBegin = execution("begin", false);
        TransactionExecution failedRollback = execution("rollback", false);
        listener.afterBegin(outer, null);
        listener.afterCommit(execution("unmatched", false), null);
        listener.afterBegin(failedBegin, new IllegalStateException("secret document or credential"));
        listener.afterBegin(failedRollback, null);
        listener.afterRollback(failedRollback, new IllegalStateException("secret document or credential"));
        listener.afterCommit(failedBegin, null);
        listener.afterCommit(outer, null);
        assertThat(recorder.recent())
                .extracting(TransactionEntryDto::methodName)
                .containsExactly("outer", "rollback", "begin");
        assertThat(recorder.recent())
                .extracting(TransactionEntryDto::status)
                .containsExactly("COMMITTED", "UNKNOWN", "UNKNOWN");
        assertThat(recorder.recent())
                .filteredOn(entry -> entry.errorMessage() != null)
                .allSatisfy(entry -> assertThat(entry.errorMessage()).doesNotContain("secret"));
        assertThat(recorder.recent().get(2).propagation()).isEqualTo("UNKNOWN");
    }

    @Test
    void unboundListenerFailsClosedForUnknownManagerMetadata() {
        TransactionRecorder recorder = recorder();
        BootUiTransactionExecutionListener listener = new BootUiTransactionExecutionListener(recorder);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_SERIALIZABLE);
        MDC.put("traceId", "stale");
        TransactionExecution execution = execution("unknown", false);
        listener.afterBegin(execution, null);
        listener.afterCommit(execution, null);
        assertThat(recorder.recent()).singleElement().satisfies(entry -> {
            assertThat(entry.managerType()).isEqualTo("UNKNOWN");
            assertThat(entry.executionKind()).isEqualTo("UNKNOWN");
            assertThat(entry.correlationStatus()).isEqualTo("UNAVAILABLE");
            assertThat(entry.isolation()).isEqualTo("UNKNOWN");
            assertThat(entry.traceId()).isNull();
        });
    }

    @Test
    void nonMongoReactiveManagerDoesNotInheritJdbcEvidenceOrClaimMongoCorrelation() {
        TransactionRecorder recorder = recorder();
        var manager = mock(
                org.springframework.transaction.ConfigurableTransactionManager.class,
                org.mockito.Mockito.withSettings()
                        .extraInterfaces(org.springframework.transaction.ReactiveTransactionManager.class));
        var listener = new BootUiTransactionExecutionListener(recorder).forManager(manager);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
        MDC.put("traceId", "stale");
        TransactionExecution execution = execution("reactive", false);
        listener.afterBegin(execution, null);
        listener.afterCommit(execution, new IllegalStateException("driver details"));
        assertThat(recorder.recent()).singleElement().satisfies(entry -> {
            assertThat(entry.status()).isEqualTo("UNKNOWN");
            assertThat(entry.executionKind()).isEqualTo("REACTIVE");
            assertThat(entry.correlationStatus()).isEqualTo("UNAVAILABLE");
            assertThat(entry.isolation()).isEqualTo("UNKNOWN");
            assertThat(entry.traceId()).isNull();
            assertThat(entry.errorMessage()).doesNotContain("driver details");
        });
    }

    private static BootUiTransactionExecutionListener jdbcListener(TransactionRecorder recorder) {
        return new BootUiTransactionExecutionListener(recorder).forManager(new DataSourceTransactionManager());
    }

    private static BootUiTransactionExecutionListener reactiveListener(TransactionRecorder recorder) {
        return new BootUiTransactionExecutionListener(recorder)
                .forManager(new ReactiveMongoTransactionManager(mock(ReactiveMongoDatabaseFactory.class)));
    }

    private static TransactionRecorder recorder() {
        return new TransactionRecorder(true, true, 10, 100, 100, null);
    }

    private static TransactionExecution execution(String name, boolean readOnly) {
        TransactionExecution execution = mock(TransactionExecution.class);
        when(execution.getTransactionName()).thenReturn(name);
        when(execution.isReadOnly()).thenReturn(readOnly);
        return execution;
    }
}
