package io.github.jdubois.bootui.engine.transactions;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.TransactionEntryDto;
import io.github.jdubois.bootui.core.dto.TransactionReport;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder.Category;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder.StatementType;
import io.github.jdubois.bootui.engine.transactions.TransactionRecorder.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TransactionRecorderTests {

    private TransactionRecorder recorder(boolean enabled, int maxEntries, long slowMillis, long connectionHoldMillis) {
        return new TransactionRecorder(enabled, true, maxEntries, slowMillis, connectionHoldMillis, null);
    }

    @Test
    void beginTransactionReturnsSentinelWhenDisabled() {
        TransactionRecorder recorder = recorder(false, 10, 100, 100);
        long id = recorder.beginTransaction("Service.method", false, "READ_COMMITTED", "main", null);
        assertThat(id).isEqualTo(-1);
        recorder.completeTransaction(id, Status.COMMITTED, null);
        assertThat(recorder.recent()).isEmpty();
        assertThat(recorder.totalCaptured()).isZero();
    }

    @Test
    void beginTransactionReturnsSentinelWhenPaused() {
        TransactionRecorder recorder = recorder(true, 10, 100, 100);
        recorder.setRecording(false);
        long id = recorder.beginTransaction("Service.method", false, "READ_COMMITTED", "main", null);
        assertThat(id).isEqualTo(-1);

        recorder.setRecording(true);
        long resumedId = recorder.beginTransaction("Service.method", false, "READ_COMMITTED", "main", null);
        assertThat(resumedId).isNotEqualTo(-1);
        recorder.completeTransaction(resumedId, Status.COMMITTED, null);
        assertThat(recorder.recent()).hasSize(1);
    }

    @Test
    void recordsACommittedRootTransaction() {
        TransactionRecorder recorder = recorder(true, 10, 100, 100);
        long id = recorder.beginTransaction("OrderService.placeOrder", false, "READ_COMMITTED", "http-1", "trace-1");
        recorder.completeTransaction(id, Status.COMMITTED, null);

        List<TransactionEntryDto> entries = recorder.recent();
        assertThat(entries).hasSize(1);
        TransactionEntryDto entry = entries.get(0);
        assertThat(entry.methodName()).isEqualTo("OrderService.placeOrder");
        assertThat(entry.propagation()).isEqualTo(TransactionRecorder.PROPAGATION_NEW);
        assertThat(entry.isolation()).isEqualTo("READ_COMMITTED");
        assertThat(entry.status()).isEqualTo("COMMITTED");
        assertThat(entry.parentId()).isNull();
        assertThat(entry.thread()).isEqualTo("http-1");
        assertThat(entry.traceId()).isEqualTo("trace-1");
        assertThat(recorder.totalCaptured()).isEqualTo(1);
    }

    @Test
    void recordsARolledBackTransactionWithErrorMessage() {
        TransactionRecorder recorder = recorder(true, 10, 100, 100);
        long id = recorder.beginTransaction("Service.method", false, null, "main", null);
        recorder.completeTransaction(id, Status.ROLLED_BACK, "boom");

        TransactionEntryDto entry = recorder.recent().get(0);
        assertThat(entry.status()).isEqualTo("ROLLED_BACK");
        assertThat(entry.errorMessage()).isEqualTo("boom");
        assertThat(entry.isolation()).isEqualTo(TransactionRecorder.ISOLATION_UNKNOWN);
    }

    @Test
    void tracksParentChildNestingOnTheSameThread() {
        TransactionRecorder recorder = recorder(true, 10, 100, 100);
        long parentId = recorder.beginTransaction("Outer.method", false, "READ_COMMITTED", "main", null);
        long childId = recorder.beginTransaction("Inner.method", false, "READ_COMMITTED", "main", null);
        recorder.completeTransaction(childId, Status.COMMITTED, null);
        recorder.completeTransaction(parentId, Status.COMMITTED, null);

        List<TransactionEntryDto> entries = recorder.recent();
        TransactionEntryDto child =
                entries.stream().filter(e -> e.id() == childId).findFirst().orElseThrow();
        TransactionEntryDto parent =
                entries.stream().filter(e -> e.id() == parentId).findFirst().orElseThrow();
        assertThat(child.propagation()).isEqualTo(TransactionRecorder.PROPAGATION_PARTICIPATING);
        assertThat(child.parentId()).isEqualTo(parentId);
        assertThat(parent.propagation()).isEqualTo(TransactionRecorder.PROPAGATION_NEW);
        assertThat(parent.parentId()).isNull();
        assertThat(recorder.stats().nestedCount()).isEqualTo(1);
    }

    @Test
    void evictsOldestEntryOnceBufferIsFull() {
        TransactionRecorder recorder = recorder(true, 2, 100, 100);
        for (int i = 0; i < 3; i++) {
            long id = recorder.beginTransaction("Service.method" + i, false, "READ_COMMITTED", "main", null);
            recorder.completeTransaction(id, Status.COMMITTED, null);
        }
        assertThat(recorder.recent()).hasSize(2);
        assertThat(recorder.evicted()).isEqualTo(1);
        assertThat(recorder.totalCaptured()).isEqualTo(3);
        // Most recently completed first.
        assertThat(recorder.recent().get(0).methodName()).isEqualTo("Service.method2");
    }

    @Test
    void flagsSlowAndConnectionHeldTransactions() throws InterruptedException {
        TransactionRecorder recorder = recorder(true, 10, 5, 5);
        long id = recorder.beginTransaction("Service.slowMethod", false, "READ_COMMITTED", "main", null);
        Thread.sleep(20);
        recorder.completeTransaction(id, Status.COMMITTED, null);

        TransactionEntryDto entry = recorder.recent().get(0);
        assertThat(entry.slow()).isTrue();
        assertThat(entry.connectionHeld()).isTrue();
        assertThat(recorder.stats().slowTransactions()).isEqualTo(1);
        assertThat(recorder.stats().connectionHeldTransactions()).isEqualTo(1);
    }

    @Test
    void clearEmptiesTheBufferWithoutResettingTotalCaptured() {
        TransactionRecorder recorder = recorder(true, 10, 100, 100);
        long id = recorder.beginTransaction("Service.method", false, "READ_COMMITTED", "main", null);
        recorder.completeTransaction(id, Status.COMMITTED, null);
        assertThat(recorder.recent()).hasSize(1);

        recorder.clear();
        assertThat(recorder.recent()).isEmpty();
        assertThat(recorder.totalCaptured()).isEqualTo(1);
    }

    @Test
    void suspendForIdleClearsAndStopsCaptureUntilResumed() {
        TransactionRecorder recorder = recorder(true, 10, 100, 100);
        long id = recorder.beginTransaction("Service.method", false, "READ_COMMITTED", "main", null);
        recorder.completeTransaction(id, Status.COMMITTED, null);
        assertThat(recorder.recent()).hasSize(1);

        recorder.suspendForIdle();
        assertThat(recorder.recent()).isEmpty();
        long suspendedId = recorder.beginTransaction("Service.other", false, "READ_COMMITTED", "main", null);
        assertThat(suspendedId).isEqualTo(-1);

        recorder.resumeFromIdle();
        long resumedId = recorder.beginTransaction("Service.other", false, "READ_COMMITTED", "main", null);
        assertThat(resumedId).isNotEqualTo(-1);
        recorder.completeTransaction(resumedId, Status.COMMITTED, null);
        assertThat(recorder.recent()).hasSize(1);
    }

    @Test
    void completeTransactionIgnoresSentinelAndUnknownIds() {
        TransactionRecorder recorder = recorder(true, 10, 100, 100);
        recorder.completeTransaction(-1, Status.COMMITTED, null);
        recorder.completeTransaction(9999, Status.COMMITTED, null);
        assertThat(recorder.recent()).isEmpty();
        assertThat(recorder.totalCaptured()).isZero();
    }

    @Test
    void subscribeIsNotifiedOnCompletionClearAndRecordingToggle() {
        TransactionRecorder recorder = recorder(true, 10, 100, 100);
        int[] notifications = {0};
        Runnable unsubscribe = recorder.subscribe(() -> notifications[0]++);

        long id = recorder.beginTransaction("Service.method", false, "READ_COMMITTED", "main", null);
        recorder.completeTransaction(id, Status.COMMITTED, null);
        assertThat(notifications[0]).isEqualTo(1);

        recorder.clear();
        assertThat(notifications[0]).isEqualTo(2);

        recorder.setRecording(false);
        assertThat(notifications[0]).isEqualTo(3);

        unsubscribe.run();
        recorder.setRecording(true);
        assertThat(notifications[0]).isEqualTo(3);
    }

    @Test
    void reportReflectsUnavailableSqlTraceCorrelationWarning() {
        TransactionRecorder recorder = recorder(true, 10, 100, 100);
        long id = recorder.beginTransaction("Service.method", false, "READ_COMMITTED", "main", null);
        recorder.completeTransaction(id, Status.COMMITTED, null);

        TransactionReport report = recorder.report();
        assertThat(report.available()).isTrue();
        assertThat(report.warnings()).anyMatch(w -> w.contains("SQL Trace is not active"));
        assertThat(report.entries()).hasSize(1);
        assertThat(report.entries().get(0).sqlStatementCount()).isZero();
        assertThat(report.entries().get(0).connectionCount()).isZero();
    }

    @Test
    void correlatesCompletedTransactionToSqlTraceExecutionsOnTheSameThread() throws InterruptedException {
        SqlTraceRecorder sqlTraceRecorder = new SqlTraceRecorder(true, true, false, false, 50, 100, 2000, 200, 5);
        TransactionRecorder recorder = new TransactionRecorder(true, true, 10, 100, 100, sqlTraceRecorder);

        long id = recorder.beginTransaction("Service.method", false, "READ_COMMITTED", "worker-1", null);
        sqlTraceRecorder.record(
                StatementType.STATEMENT,
                Category.SELECT,
                "select 1",
                List.of(),
                1,
                true,
                null,
                null,
                0,
                "conn-1",
                "worker-1");
        sqlTraceRecorder.record(
                StatementType.STATEMENT,
                Category.INSERT,
                "insert into t values (1)",
                List.of(),
                1,
                true,
                null,
                null,
                0,
                "conn-1",
                "worker-1");
        // A statement on a different thread must not be attributed to this transaction.
        sqlTraceRecorder.record(
                StatementType.STATEMENT,
                Category.SELECT,
                "select 2",
                List.of(),
                1,
                true,
                null,
                null,
                0,
                "conn-2",
                "worker-2");
        recorder.completeTransaction(id, Status.COMMITTED, null);

        TransactionEntryDto entry = recorder.recent().get(0);
        assertThat(entry.sqlStatementCount()).isEqualTo(2);
        assertThat(entry.connectionCount()).isEqualTo(1);
    }

    @Test
    void unavailableFactoryReportsReasonWithNoStatsOrEntries() {
        TransactionReport report = TransactionReport.unavailable("No PlatformTransactionManager bean is available");
        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).isEqualTo("No PlatformTransactionManager bean is available");
        assertThat(report.entries()).isEmpty();
        assertThat(report.stats().totalTransactions()).isZero();
    }

    @Test
    void detachedIdentitySurvivesThreadHopsWithoutBorrowingJdbcOrMdcEvidence() throws Exception {
        SqlTraceRecorder sql = new SqlTraceRecorder(true, true, false, false, 50, 100, 2000, 200, 5);
        TransactionRecorder recorder = new TransactionRecorder(true, true, 10, 1, 1, sql);
        long jdbc = recorder.beginTransaction("jdbc", false, "SERIALIZABLE", "worker", "jdbc-trace");
        Object mongo = new Object();
        detached(recorder, mongo, "mongo");
        sql.record(
                StatementType.STATEMENT,
                Category.SELECT,
                "select 1",
                List.of(),
                1,
                true,
                null,
                null,
                0,
                "jdbc-connection",
                "worker");
        Thread.sleep(20);
        var executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> recorder.completeDetachedTransaction(mongo, Status.COMMITTED, null))
                    .get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        recorder.completeTransaction(jdbc, Status.COMMITTED, null);

        TransactionEntryDto mongoEntry = recorder.recent().stream()
                .filter(entry -> entry.methodName().equals("mongo"))
                .findFirst()
                .orElseThrow();
        assertThat(mongoEntry.parentId()).isNull();
        assertThat(mongoEntry.traceId()).isNull();
        assertThat(mongoEntry.isolation()).isEqualTo("UNKNOWN");
        assertThat(mongoEntry.sqlStatementCount()).isZero();
        assertThat(mongoEntry.connectionCount()).isZero();
        assertThat(mongoEntry.connectionHeld()).isFalse();
        assertThat(recorder.isConnectionHeld(mongoEntry.durationMillis())).isTrue();
        assertThat(mongoEntry.correlationStatus()).isEqualTo("NOT_APPLICABLE");
        assertThat(mongoEntry.limitations()).isNotEmpty();
        assertThat(recorder.recent().get(0).sqlStatementCount()).isEqualTo(1);
        long next = recorder.beginTransaction("next", false, null, "worker", null);
        recorder.completeTransaction(next, Status.COMMITTED, null);
        assertThat(recorder.recent().get(0).parentId()).isNull();
    }

    @Test
    void equalButDistinctExecutionsDoNotShareAnAssociationAndDuplicateCallbacksAreIgnored() {
        TransactionRecorder recorder = recorder(true, 10, 100, 100);
        Object first = new String("equal");
        Object second = new String("equal");
        detached(recorder, first, "first");
        detached(recorder, second, "second");
        detached(recorder, first, "duplicate");
        recorder.completeDetachedTransaction(new String("equal"), Status.COMMITTED, null);
        assertThat(recorder.recent()).isEmpty();
        recorder.completeDetachedTransaction(first, Status.COMMITTED, null);
        recorder.completeDetachedTransaction(second, Status.UNKNOWN, "rollback failed");
        recorder.completeDetachedTransaction(first, Status.ROLLED_BACK, null);
        assertThat(recorder.recent())
                .extracting(TransactionEntryDto::methodName)
                .containsExactly("second", "first");
        assertThat(recorder.recent()).extracting(TransactionEntryDto::status).containsExactly("UNKNOWN", "COMMITTED");
        assertThat(recorder.recent())
                .allSatisfy(entry -> assertThat(entry.parentId()).isNull());
    }

    @Test
    void detachedCapacityEvictsIncompleteObservationsInsteadOfRetainingExecutionsForever() {
        TransactionRecorder recorder = recorder(true, 2, 100, 100);
        Object abandoned = new Object();
        Object second = new Object();
        Object third = new Object();
        detached(recorder, abandoned, "abandoned");
        detached(recorder, second, "second");
        detached(recorder, third, "third");
        assertThat(recorder.recent()).singleElement().satisfies(entry -> {
            assertThat(entry.methodName()).isEqualTo("abandoned");
            assertThat(entry.status()).isEqualTo("UNKNOWN");
            assertThat(entry.errorMessage()).isNull();
            assertThat(entry.limitations()).anyMatch(reason -> reason.contains("in-flight capture limit"));
        });
        recorder.completeDetachedTransaction(abandoned, Status.COMMITTED, null);
        assertThat(recorder.totalCaptured()).isEqualTo(1);
        recorder.completeDetachedTransaction(second, Status.COMMITTED, null);
        recorder.completeDetachedTransaction(third, Status.COMMITTED, null);
        assertThat(recorder.totalCaptured()).isEqualTo(3);
        assertThat(recorder.report().warnings()).anyMatch(warning -> warning.contains("1 detached transaction"));
    }

    @Test
    void hardCapAppliesEvenWhenTheCompletedBufferIsConfiguredLarger() {
        TransactionRecorder recorder = recorder(true, 2000, 100, 100);
        List<Object> executions = new ArrayList<>();
        for (int index = 0; index < 1030; index++) {
            Object execution = new Object();
            executions.add(execution);
            detached(recorder, execution, "transaction-" + index);
        }
        assertThat(recorder.stats().unknownCount()).isEqualTo(6);
        for (Object execution : executions) {
            recorder.completeDetachedTransaction(execution, Status.COMMITTED, null);
        }
        assertThat(recorder.stats().committedCount()).isEqualTo(1024);
        assertThat(recorder.totalCaptured()).isEqualTo(1030);
    }

    @Test
    void missingOrCancelledCompletionExpiresOnReportAccessUsingMonotonicTime() {
        AtomicLong ticker = new AtomicLong();
        TransactionRecorder recorder = new TransactionRecorder(true, true, 10, 100, 100, null, ticker::get);
        Object cancelled = new Object();
        detached(recorder, cancelled, "cancelled");
        ticker.set(TransactionRecorder.DETACHED_MAX_AGE_NANOS - 1);
        assertThat(recorder.recent()).isEmpty();
        ticker.incrementAndGet();
        TransactionReport expired = recorder.report();
        assertThat(expired.totalCaptured()).isEqualTo(1);
        assertThat(expired.stats().unknownCount()).isEqualTo(1);
        assertThat(expired.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.status()).isEqualTo("UNKNOWN");
            assertThat(entry.limitations()).anyMatch(reason -> reason.contains("five-minute capture window"));
        });
        recorder.completeDetachedTransaction(cancelled, Status.COMMITTED, null);
        assertThat(recorder.totalCaptured()).isEqualTo(1);
    }

    @Test
    void recordingChangesClearAndIdleReleaseDetachedOwnershipWithoutResurrectingOldCallbacks() {
        TransactionRecorder recorder = recorder(true, 10, 100, 100);
        Object paused = new Object();
        detached(recorder, paused, "paused");
        recorder.setRecording(false);
        assertThat(recorder.recent()).singleElement().satisfies(entry -> {
            assertThat(entry.status()).isEqualTo("UNKNOWN");
            assertThat(entry.limitations()).anyMatch(reason -> reason.contains("Recording paused"));
        });
        Object whilePaused = new Object();
        detached(recorder, whilePaused, "not-captured");
        recorder.setRecording(true);
        recorder.completeDetachedTransaction(paused, Status.COMMITTED, null);
        recorder.completeDetachedTransaction(whilePaused, Status.COMMITTED, null);
        assertThat(recorder.totalCaptured()).isEqualTo(1);
        Object cleared = new Object();
        detached(recorder, cleared, "cleared");
        recorder.clear();
        recorder.completeDetachedTransaction(cleared, Status.COMMITTED, null);
        assertThat(recorder.recent()).isEmpty();
        Object idle = new Object();
        detached(recorder, idle, "idle");
        recorder.suspendForIdle();
        detached(recorder, new Object(), "suspended");
        recorder.resumeFromIdle();
        recorder.completeDetachedTransaction(idle, Status.COMMITTED, null);
        assertThat(recorder.recent()).isEmpty();
        assertThat(recorder.report().warnings()).anyMatch(warning -> warning.contains("2 in-flight detached"));
    }

    @Test
    void disabledDetachedCaptureAndUnknownMetadataDoNotInventEvidence() {
        TransactionRecorder disabled = recorder(false, 10, 100, 100);
        Object execution = new Object();
        detached(disabled, execution, "disabled");
        disabled.completeDetachedTransaction(execution, Status.COMMITTED, null);
        assertThat(disabled.recent()).isEmpty();
        TransactionRecorder recorder = recorder(true, 10, 100, 100);
        recorder.beginDetachedTransaction(execution, null, false, false, null, null, null, false);
        recorder.completeDetachedTransaction(execution, null, null);
        assertThat(recorder.recent()).singleElement().satisfies(entry -> {
            assertThat(entry.methodName()).isEqualTo("unknown");
            assertThat(entry.propagation()).isEqualTo("UNKNOWN");
            assertThat(entry.managerType()).isEqualTo("UNKNOWN");
            assertThat(entry.executionKind()).isEqualTo("UNKNOWN");
            assertThat(entry.correlationStatus()).isEqualTo("UNAVAILABLE");
            assertThat(entry.thread()).isNull();
            assertThat(entry.status()).isEqualTo("UNKNOWN");
        });
    }

    @Test
    void legacyDtoConstructorAndNewLimitationsRemainCompatibleAndImmutable() {
        TransactionEntryDto legacy = new TransactionEntryDto(
                1,
                "method",
                "NEW",
                "UNKNOWN",
                "COMMITTED",
                1,
                2,
                1,
                null,
                "worker",
                null,
                0,
                0,
                false,
                false,
                false,
                null);
        assertThat(legacy.managerType()).isEqualTo("UNKNOWN");
        assertThat(legacy.limitations()).isEmpty();
        List<String> limitations = new ArrayList<>(List.of("No trace context"));
        TransactionEntryDto entry = new TransactionEntryDto(
                1,
                "method",
                "NEW",
                "UNKNOWN",
                "COMMITTED",
                1,
                2,
                1,
                null,
                "worker",
                null,
                0,
                0,
                false,
                false,
                false,
                null,
                "MongoTransactionManager",
                "IMPERATIVE",
                "NOT_APPLICABLE",
                limitations);
        limitations.clear();
        assertThat(entry.limitations()).containsExactly("No trace context");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> entry.limitations().add("changed"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static void detached(TransactionRecorder recorder, Object execution, String name) {
        recorder.beginDetachedTransaction(
                execution, name, false, true, "worker", "MongoTransactionManager", "REACTIVE", true);
    }
}
