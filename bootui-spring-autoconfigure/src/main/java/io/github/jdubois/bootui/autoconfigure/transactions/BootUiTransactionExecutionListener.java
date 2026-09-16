package io.github.jdubois.bootui.autoconfigure.transactions;

import io.github.jdubois.bootui.engine.transactions.TransactionRecorder;
import io.github.jdubois.bootui.engine.transactions.TransactionRecorder.Status;
import java.sql.Connection;
import java.util.ArrayDeque;
import java.util.Deque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Bridges Spring Framework's {@code TransactionExecutionListener} (Spring 6.1+) to the
 * framework-neutral {@link TransactionRecorder}. Exposed as a Spring bean so Spring Boot's standard
 * transaction-manager customization registers it against every {@code ConfigurableTransactionManager};
 * it composes with, and never replaces, the application's own transaction management or any other
 * listener.
 *
 * <p>The registrar binds a bridge to each manager. MongoDB, reactive, and unbound callbacks use a
 * bounded execution-identity association in the recorder, never thread-local JDBC evidence. Known
 * imperative non-MongoDB managers retain the existing thread-stack and SQL time-window heuristic.</p>
 *
 * <p>Every callback is fully guarded: a recorder failure must never fail, roll back, or otherwise
 * disrupt the application's actual transaction.</p>
 */
public final class BootUiTransactionExecutionListener implements TransactionExecutionListener {

    private static final Logger LOG = LoggerFactory.getLogger(BootUiTransactionExecutionListener.class);
    private final TransactionRecorder recorder;
    private final BootUiTransactionExecutionListener owner;
    private final String managerType;
    private final String executionKind;
    private final boolean detached;
    private final boolean mongoDb;
    private final ThreadLocal<Deque<Long>> pending = ThreadLocal.withInitial(ArrayDeque::new);

    public BootUiTransactionExecutionListener(TransactionRecorder recorder) {
        this.recorder = recorder;
        this.owner = this;
        this.managerType = "UNKNOWN";
        this.executionKind = "UNKNOWN";
        this.detached = true;
        this.mongoDb = false;
    }

    private BootUiTransactionExecutionListener(
            BootUiTransactionExecutionListener owner, ConfigurableTransactionManager manager) {
        this.recorder = owner.recorder;
        this.owner = owner.owner;
        this.managerType = manager.getClass().getName();
        this.mongoDb = hasType(manager.getClass(), "org.springframework.data.mongodb.MongoTransactionManager")
                || hasType(manager.getClass(), "org.springframework.data.mongodb.ReactiveMongoTransactionManager");
        boolean reactive = manager instanceof ReactiveTransactionManager;
        this.executionKind = reactive ? "REACTIVE" : "IMPERATIVE";
        this.detached = mongoDb || reactive;
    }

    BootUiTransactionExecutionListener forManager(ConfigurableTransactionManager manager) {
        return new BootUiTransactionExecutionListener(this, manager);
    }

    boolean belongsTo(BootUiTransactionExecutionListener listener) {
        return owner == listener.owner;
    }

    private static boolean hasType(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (current.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void afterBegin(TransactionExecution transactionExecution, Throwable beginFailure) {
        try {
            String name = transactionName(transactionExecution);
            boolean readOnly = transactionExecution.isReadOnly();
            String thread = Thread.currentThread().getName();
            if (detached) {
                recorder.beginDetachedTransaction(
                        transactionExecution,
                        name,
                        readOnly,
                        beginFailure == null && transactionExecution.isNewTransaction(),
                        thread,
                        managerType,
                        hasType(
                                        transactionExecution.getClass(),
                                        "org.springframework.transaction.reactive.GenericReactiveTransaction")
                                ? "REACTIVE"
                                : executionKind,
                        mongoDb);
                if (beginFailure != null) {
                    recorder.completeDetachedTransaction(
                            transactionExecution, Status.UNKNOWN, "Transaction begin failed; the outcome is unknown.");
                }
                return;
            }
            String traceId = mdcTraceId();
            if (beginFailure != null) {
                long id = recorder.beginTransaction(name, readOnly, null, thread, traceId, managerType);
                recorder.completeTransaction(id, Status.UNKNOWN, message(beginFailure));
                return;
            }
            long id = recorder.beginTransaction(name, readOnly, currentIsolation(), thread, traceId, managerType);
            pending.get().addLast(id);
        } catch (RuntimeException ex) {
            LOG.warn("BootUI could not record a transaction begin callback; application processing is unchanged.");
        }
    }

    @Override
    public void afterCommit(TransactionExecution transactionExecution, Throwable commitFailure) {
        complete(transactionExecution, commitFailure == null ? Status.COMMITTED : Status.UNKNOWN, commitFailure);
    }

    @Override
    public void afterRollback(TransactionExecution transactionExecution, Throwable rollbackFailure) {
        complete(transactionExecution, rollbackFailure == null ? Status.ROLLED_BACK : Status.UNKNOWN, rollbackFailure);
    }

    private void complete(TransactionExecution execution, Status status, Throwable failure) {
        try {
            if (detached) {
                recorder.completeDetachedTransaction(
                        execution,
                        status,
                        failure == null ? null : "Transaction completion failed; the outcome is unknown.");
                return;
            }
            Long id = popPending();
            if (id != null) {
                recorder.completeTransaction(id, status, message(failure));
            }
        } catch (RuntimeException ex) {
            LOG.warn("BootUI could not record a transaction completion callback; application processing is unchanged.");
        }
    }

    private Long popPending() {
        Deque<Long> stack = pending.get();
        Long id = stack.pollLast();
        if (stack.isEmpty()) {
            pending.remove();
        }
        return id;
    }

    private static String transactionName(TransactionExecution transactionExecution) {
        String name = transactionExecution.getTransactionName();
        return name == null || name.isBlank() ? "unknown" : name;
    }

    private static String message(Throwable failure) {
        return failure == null ? null : failure.getMessage();
    }

    /**
     * Reads the JDBC isolation level {@code AbstractPlatformTransactionManager} bound to the current
     * transaction synchronization once {@code doBegin} completes, mapped to its readable {@code
     * java.sql.Connection} constant name. Returns {@code null} (recorded as {@code UNKNOWN}) when no
     * isolation level was bound, e.g. a resource-less transaction or a manager that leaves the default.
     */
    private static String currentIsolation() {
        try {
            Integer level = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
            if (level == null) {
                return null;
            }
            return switch (level) {
                case Connection.TRANSACTION_READ_UNCOMMITTED -> "READ_UNCOMMITTED";
                case Connection.TRANSACTION_READ_COMMITTED -> "READ_COMMITTED";
                case Connection.TRANSACTION_REPEATABLE_READ -> "REPEATABLE_READ";
                case Connection.TRANSACTION_SERIALIZABLE -> "SERIALIZABLE";
                default -> "UNKNOWN";
            };
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Same SLF4J MDC {@code traceId} lookup {@code SqlTraceRecorder} uses by default: the correlation
     * key Micrometer Tracing publishes, read defensively so a missing or misbehaving MDC never disrupts
     * the transaction being observed.
     */
    private static String mdcTraceId() {
        try {
            String traceId = org.slf4j.MDC.get("traceId");
            return traceId == null || traceId.isBlank() ? null : traceId;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
