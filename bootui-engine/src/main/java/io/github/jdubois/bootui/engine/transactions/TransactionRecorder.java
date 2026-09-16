package io.github.jdubois.bootui.engine.transactions;

import io.github.jdubois.bootui.core.dto.TransactionEntryDto;
import io.github.jdubois.bootui.core.dto.TransactionReport;
import io.github.jdubois.bootui.core.dto.TransactionStatsDto;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.spi.IdleReclaimable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * In-memory, bounded buffer of recently completed transaction boundaries.
 *
 * <p>This is the hand-written replacement for the listener/registry that a third-party
 * transaction-observability library (such as spring-tx-board) would provide. It is thread-safe,
 * capped at {@code maxEntries}, and evicts the oldest transaction once full so it never grows
 * unbounded, mirroring {@link SqlTraceRecorder}'s buffer shape.</p>
 *
 * <p>A transaction is recorded in two steps: {@link #beginTransaction} when the boundary starts
 * (from an {@code afterBegin} callback) and {@link #completeTransaction} when it finishes (from an
 * {@code afterCommit}/{@code afterRollback} callback). Nesting is tracked with a per-thread stack of
 * in-flight transaction ids so a participating transaction records the id of the transaction already
 * active on the same thread as its {@code parentId}.</p>
 *
 * <p>When a {@link SqlTraceRecorder} is supplied, each completed transaction is correlated to the SQL
 * it likely ran by reusing that recorder's already-captured executions: statements on the same thread
 * whose completion timestamp falls within the transaction's begin/end window are counted, and their
 * distinct connection ids are counted separately. This is the same thread/time-window correlation
 * heuristic {@code SqlTraceRecorder} itself falls back to when no trace id is available, applied here
 * rather than duplicated.</p>
 */
public final class TransactionRecorder implements IdleReclaimable {

    /** Outcome of a completed transaction boundary. */
    public enum Status {
        COMMITTED,
        ROLLED_BACK,
        UNKNOWN
    }

    /** Best-effort propagation classification; see {@link TransactionEntryDto#propagation()}. */
    public static final String PROPAGATION_NEW = "NEW";

    public static final String PROPAGATION_PARTICIPATING = "PARTICIPATING";

    public static final String ISOLATION_UNKNOWN = "UNKNOWN";

    static final long DETACHED_MAX_AGE_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final int DETACHED_MAX_ENTRIES = 1024;
    private static final List<String> DETACHED_LIMITATIONS = List.of(
            "Outcomes describe manager callbacks, not independent server verification.",
            "Parent and trace context are not observed by these transaction callbacks.",
            "Thread identifies the begin callback only, not transaction ownership.",
            "JDBC isolation, SQL counts and connection-hold evidence are not observed.");

    private final boolean enabled;
    private final int maxEntries;
    private final long slowTransactionThresholdMillis;
    private final long connectionHoldThresholdMillis;
    private final SqlTraceRecorder sqlTraceRecorder;
    private final LongSupplier nanoTime;
    private final Map<IdentityKey, ActiveTransaction> detached = new LinkedHashMap<>();
    private final AtomicLong incompleteDetached = new AtomicLong();
    private final AtomicLong discardedDetached = new AtomicLong();
    private volatile boolean detachedCaptureSeen;

    private final Deque<TransactionEntryDto> buffer = new ArrayDeque<>();
    private final Object lock = new Object();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong totalCaptured = new AtomicLong();
    private final AtomicLong evicted = new AtomicLong();
    private final AtomicBoolean recording;
    private volatile boolean idleSuspended = false;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    private final Map<Long, ActiveTransaction> active = new ConcurrentHashMap<>();
    private final ThreadLocal<Deque<Long>> threadStack = ThreadLocal.withInitial(ArrayDeque::new);

    public TransactionRecorder(
            boolean enabled,
            boolean recording,
            int maxEntries,
            long slowTransactionThresholdMillis,
            long connectionHoldThresholdMillis,
            SqlTraceRecorder sqlTraceRecorder) {
        this(
                enabled,
                recording,
                maxEntries,
                slowTransactionThresholdMillis,
                connectionHoldThresholdMillis,
                sqlTraceRecorder,
                System::nanoTime);
    }

    TransactionRecorder(
            boolean enabled,
            boolean recording,
            int maxEntries,
            long slowTransactionThresholdMillis,
            long connectionHoldThresholdMillis,
            SqlTraceRecorder sqlTraceRecorder,
            LongSupplier nanoTime) {
        this.enabled = enabled;
        this.recording = new AtomicBoolean(recording);
        this.maxEntries = Math.max(1, maxEntries);
        this.slowTransactionThresholdMillis = Math.max(0, slowTransactionThresholdMillis);
        this.connectionHoldThresholdMillis = Math.max(0, connectionHoldThresholdMillis);
        this.sqlTraceRecorder = sqlTraceRecorder;
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isRecording() {
        return recording.get();
    }

    public void setRecording(boolean value) {
        boolean changed;
        synchronized (lock) {
            changed = recording.getAndSet(value) != value;
            if (changed && !value) {
                for (ActiveTransaction transaction : detached.values()) {
                    retainIncomplete(transaction, "Recording paused before completion was observed.");
                }
                detached.clear();
            }
        }
        if (changed) {
            notifyListeners();
        }
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public long getSlowTransactionThresholdMillis() {
        return slowTransactionThresholdMillis;
    }

    public long getConnectionHoldThresholdMillis() {
        return connectionHoldThresholdMillis;
    }

    public boolean isSlow(long durationMillis) {
        return slowTransactionThresholdMillis > 0 && durationMillis >= slowTransactionThresholdMillis;
    }

    public boolean isConnectionHeld(long durationMillis) {
        return connectionHoldThresholdMillis > 0 && durationMillis >= connectionHoldThresholdMillis;
    }

    private boolean isActive() {
        return enabled && !idleSuspended && recording.get();
    }

    /**
     * Records the start of a transaction boundary, resolving its parent from the thread's stack of
     * already-active transactions. Returns {@code -1} (a sentinel completion no-ops on) when capture is
     * disabled, paused, or idle-suspended, so callers never need a null check.
     */
    public long beginTransaction(String methodName, boolean readOnly, String isolation, String thread, String traceId) {
        return beginTransaction(methodName, readOnly, isolation, thread, traceId, "UNKNOWN");
    }

    public long beginTransaction(
            String methodName, boolean readOnly, String isolation, String thread, String traceId, String managerType) {
        if (!isActive()) {
            return -1;
        }
        long id = sequence.incrementAndGet();
        Deque<Long> stack = threadStack.get();
        Long parentId = stack.peekLast();
        ActiveTransaction transaction = new ActiveTransaction(
                id,
                methodName == null ? "unknown" : methodName,
                parentId == null ? PROPAGATION_NEW : PROPAGATION_PARTICIPATING,
                isolation == null || isolation.isBlank() ? ISOLATION_UNKNOWN : isolation,
                readOnly,
                parentId,
                thread,
                traceId,
                System.currentTimeMillis(),
                false,
                managerType,
                "IMPERATIVE",
                sqlTraceRecorder == null || thread == null ? "UNAVAILABLE" : "THREAD_TIME_WINDOW",
                0);
        active.put(id, transaction);
        stack.addLast(id);
        return id;
    }

    /**
     * Associates callbacks by object identity without touching JDBC or thread-local state. The caller
     * must pass the same execution object at completion. Missing callbacks retain at most
     * {@code min(maxEntries, 1024)} associations, aged out after five minutes on capture or report access.
     * Expiration describes an incomplete observation, never an inferred application rollback.
     */
    public void beginDetachedTransaction(
            Object execution,
            String methodName,
            boolean readOnly,
            boolean newTransaction,
            String thread,
            String managerType,
            String executionKind,
            boolean mongoDb) {
        Objects.requireNonNull(execution, "execution");
        boolean changed;
        synchronized (lock) {
            if (!isActive()) {
                return;
            }
            detachedCaptureSeen = true;
            long now = nanoTime.getAsLong();
            changed = expireDetached(now);
            IdentityKey key = new IdentityKey(execution);
            if (!detached.containsKey(key)) {
                if (detached.size() >= Math.min(maxEntries, DETACHED_MAX_ENTRIES)) {
                    var oldest = detached.entrySet().iterator();
                    retainIncomplete(
                            oldest.next().getValue(),
                            "Completion was not observed before the in-flight capture limit was reached.");
                    oldest.remove();
                    changed = true;
                }
                detached.put(
                        key,
                        new ActiveTransaction(
                                sequence.incrementAndGet(),
                                bounded(methodName == null || methodName.isBlank() ? "unknown" : methodName),
                                newTransaction ? PROPAGATION_NEW : "UNKNOWN",
                                ISOLATION_UNKNOWN,
                                readOnly,
                                null,
                                bounded(thread),
                                null,
                                System.currentTimeMillis(),
                                true,
                                bounded(managerType == null ? "UNKNOWN" : managerType),
                                "REACTIVE".equals(executionKind)
                                        ? "REACTIVE"
                                        : "IMPERATIVE".equals(executionKind) ? "IMPERATIVE" : "UNKNOWN",
                                mongoDb ? "NOT_APPLICABLE" : "UNAVAILABLE",
                                now));
            }
        }
        if (changed) {
            notifyListeners();
        }
    }

    public void completeDetachedTransaction(Object execution, Status status, String errorMessage) {
        Objects.requireNonNull(execution, "execution");
        boolean changed;
        synchronized (lock) {
            changed = expireDetached(nanoTime.getAsLong());
            ActiveTransaction transaction = detached.remove(new IdentityKey(execution));
            if (transaction != null) {
                retain(entry(
                        transaction,
                        status == null ? Status.UNKNOWN : status,
                        bounded(errorMessage),
                        DETACHED_LIMITATIONS));
                changed = true;
            }
        }
        if (changed) {
            notifyListeners();
        }
    }

    private boolean expireDetached(long now) {
        boolean changed = false;
        var iterator = detached.values().iterator();
        while (iterator.hasNext()) {
            ActiveTransaction transaction = iterator.next();
            if (now - transaction.startNanos < DETACHED_MAX_AGE_NANOS) {
                break;
            }
            retainIncomplete(transaction, "Completion was not observed within the five-minute capture window.");
            iterator.remove();
            changed = true;
        }
        return changed;
    }

    private void retainIncomplete(ActiveTransaction transaction, String reason) {
        List<String> limitations = new ArrayList<>(DETACHED_LIMITATIONS);
        limitations.add(reason);
        retain(entry(transaction, Status.UNKNOWN, null, limitations));
        incompleteDetached.incrementAndGet();
    }

    private static String bounded(String value) {
        return value == null || value.length() <= 512 ? value : value.substring(0, 512);
    }

    private static final class IdentityKey {
        private final Object execution;

        private IdentityKey(Object execution) {
            this.execution = execution;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(execution);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof IdentityKey key && execution == key.execution;
        }
    }

    /**
     * Records the completion of a transaction boundary previously started with {@link
     * #beginTransaction} (including the case where starting it failed, which the caller reports as
     * {@link Status#UNKNOWN} with an {@code errorMessage}). No-ops for the {@code -1} sentinel or an id
     * this recorder never saw begin (e.g. capture was toggled on mid-transaction).
     */
    public void completeTransaction(long id, Status status, String errorMessage) {
        if (id < 0) {
            return;
        }
        popStack(id);
        ActiveTransaction transaction = active.remove(id);
        if (transaction == null) {
            return;
        }
        record(transaction, status == null ? Status.UNKNOWN : status, errorMessage);
    }

    private void popStack(long id) {
        Deque<Long> stack = threadStack.get();
        stack.removeLastOccurrence(id);
        if (stack.isEmpty()) {
            threadStack.remove();
        }
    }

    private void record(ActiveTransaction transaction, Status status, String errorMessage) {
        TransactionEntryDto entry = entry(transaction, status, errorMessage, List.of());
        synchronized (lock) {
            retain(entry);
        }
        notifyListeners();
    }

    private TransactionEntryDto entry(
            ActiveTransaction transaction, Status status, String errorMessage, List<String> limitations) {
        long end = System.currentTimeMillis();
        long duration = Math.max(0, end - transaction.startTimestamp);
        Correlation correlation = transaction.detached ? Correlation.EMPTY : correlate(transaction, end);
        return new TransactionEntryDto(
                transaction.id,
                transaction.methodName,
                transaction.propagation,
                transaction.isolation,
                status.name(),
                transaction.startTimestamp,
                end,
                duration,
                transaction.parentId,
                transaction.thread,
                transaction.traceId,
                correlation.statementCount(),
                correlation.connectionCount(),
                transaction.readOnly,
                isSlow(duration),
                !transaction.detached && isConnectionHeld(duration),
                errorMessage,
                transaction.managerType,
                transaction.executionKind,
                transaction.correlationStatus,
                limitations);
    }

    private void retain(TransactionEntryDto entry) {
        buffer.addLast(entry);
        while (buffer.size() > maxEntries) {
            buffer.removeFirst();
            evicted.incrementAndGet();
        }
        totalCaptured.incrementAndGet();
    }

    /**
     * Correlates the completed transaction to SQL Trace executions on the same thread whose completion
     * fell within the transaction's begin/end window, reusing {@link SqlTraceRecorder#recent()} rather
     * than tracking JDBC activity a second time. Returns a zero correlation when no SQL Trace recorder
     * is wired (panel disabled or unavailable) or the transaction's thread is unknown.
     */
    private Correlation correlate(ActiveTransaction transaction, long end) {
        if (sqlTraceRecorder == null || transaction.thread == null) {
            return Correlation.EMPTY;
        }
        int statements = 0;
        Set<String> connections = new HashSet<>();
        for (SqlTraceRecorder.CapturedStatement statement : sqlTraceRecorder.recent()) {
            if (!transaction.thread.equals(statement.thread())) {
                continue;
            }
            long timestamp = statement.timestamp();
            if (timestamp < transaction.startTimestamp || timestamp > end) {
                continue;
            }
            statements++;
            if (statement.connectionId() != null) {
                connections.add(statement.connectionId());
            }
        }
        return new Correlation(statements, connections.size());
    }

    private record Correlation(int statementCount, int connectionCount) {
        private static final Correlation EMPTY = new Correlation(0, 0);
    }

    /** Returns the retained transactions, most recently completed first. */
    public List<TransactionEntryDto> recent() {
        synchronized (lock) {
            expireDetached(nanoTime.getAsLong());
            List<TransactionEntryDto> snapshot = new ArrayList<>(buffer);
            java.util.Collections.reverse(snapshot);
            return snapshot;
        }
    }

    public long totalCaptured() {
        return totalCaptured.get();
    }

    public long evicted() {
        return evicted.get();
    }

    public void clear() {
        synchronized (lock) {
            buffer.clear();
            discardedDetached.addAndGet(detached.size());
            detached.clear();
        }
        notifyListeners();
    }

    @Override
    public void suspendForIdle() {
        idleSuspended = true;
        clear();
    }

    @Override
    public void resumeFromIdle() {
        idleSuspended = false;
    }

    /**
     * Registers a listener invoked (with no payload) whenever the trace changes, i.e. on a completed
     * transaction, a {@link #clear()}, or a recording pause/resume. Returns a handle that removes the
     * listener when run. Listener failures are isolated so they cannot disrupt transaction execution.
     */
    public Runnable subscribe(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // A misbehaving stream subscriber must never disrupt transaction execution.
            }
        }
    }

    /** Computes aggregate counters over the retained buffer. */
    public TransactionStatsDto stats() {
        return stats(recent());
    }

    private TransactionStatsDto stats(List<TransactionEntryDto> snapshot) {
        long total = 0;
        long totalDuration = 0;
        long maxDuration = 0;
        long slow = 0;
        long connectionHeld = 0;
        long committed = 0;
        long rolledBack = 0;
        long unknown = 0;
        long nested = 0;
        for (TransactionEntryDto entry : snapshot) {
            total++;
            totalDuration += entry.durationMillis();
            maxDuration = Math.max(maxDuration, entry.durationMillis());
            if (entry.slow()) {
                slow++;
            }
            if (entry.connectionHeld()) {
                connectionHeld++;
            }
            if (entry.parentId() != null) {
                nested++;
            }
            switch (Status.valueOf(entry.status())) {
                case COMMITTED -> committed++;
                case ROLLED_BACK -> rolledBack++;
                case UNKNOWN -> unknown++;
            }
        }
        double avg = total == 0 ? 0 : (double) totalDuration / total;
        return new TransactionStatsDto(
                total,
                totalDuration,
                maxDuration,
                avg,
                slow,
                connectionHeld,
                committed,
                rolledBack,
                unknown,
                nested,
                evicted.get());
    }

    /**
     * Assembles the immutable {@link TransactionReport} the panel renders, shared verbatim by the
     * Spring adapter so the wire stays stable regardless of capture mechanism. The adapter decides the
     * unavailable case (no transaction manager wired); this method covers the available case.
     */
    public TransactionReport report() {
        synchronized (lock) {
            List<TransactionEntryDto> entries = recent();
            return new TransactionReport(
                    true,
                    null,
                    isRecording(),
                    getMaxEntries(),
                    totalCaptured(),
                    getSlowTransactionThresholdMillis(),
                    getConnectionHoldThresholdMillis(),
                    stats(entries),
                    entries,
                    warnings());
        }
    }

    private List<String> warnings() {
        List<String> warnings = new ArrayList<>();
        if (detachedCaptureSeen) {
            warnings.add("MongoDB/reactive capture observes manager callbacks only, not arbitrary sessions. In-flight"
                    + " associations are capped at " + Math.min(maxEntries, DETACHED_MAX_ENTRIES)
                    + " and expire after five minutes on capture or report access.");
        }
        if (incompleteDetached.get() > 0) {
            warnings.add(incompleteDetached.get()
                    + " detached transaction observations ended without a completion callback since startup;"
                    + " their application outcomes are unknown.");
        }
        if (discardedDetached.get() > 0) {
            warnings.add(discardedDetached.get()
                    + " in-flight detached observations were discarded by clear or idle suspension since startup.");
        }
        if (!isRecording()) {
            warnings.add("Recording is paused. Resume it to capture new transactions.");
        }
        if (sqlTraceRecorder == null || !sqlTraceRecorder.hasWrappedDataSource()) {
            warnings.add("SQL Trace is not active, so SQL statement/connection counts are not correlated.");
        }
        if (evicted() > 0) {
            warnings.add("Older transactions were dropped; the buffer keeps the most recent " + getMaxEntries() + ".");
        }
        return warnings;
    }

    private record ActiveTransaction(
            long id,
            String methodName,
            String propagation,
            String isolation,
            boolean readOnly,
            Long parentId,
            String thread,
            String traceId,
            long startTimestamp,
            boolean detached,
            String managerType,
            String executionKind,
            String correlationStatus,
            long startNanos) {}
}
