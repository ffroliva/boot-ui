package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * A single captured transaction boundary (begin through commit/rollback).
 *
 * <p>Populated by BootUI's hand-written transaction listener wiring (no third-party
 * transaction-observability library). Nesting is expressed through {@code parentId}: a
 * participating (non-new) transaction records the id of the transaction that was active on the
 * same thread when it began, letting the panel render a call tree.</p>
 *
 * @param id sequence number, increasing in begin order
 * @param methodName the transactional boundary's declared name, typically {@code
 *     ClassName.methodName} for {@code @Transactional} methods
 * @param propagation best-effort propagation classification: {@code NEW} for a transaction the
 *     manager actually started, or {@code PARTICIPATING} when it joined an already-active
 *     transaction on the same thread; Spring's transaction-execution listener does not expose the
 *     declared {@code Propagation} enum value itself; {@code UNKNOWN} when the callback cannot establish it
 * @param isolation the JDBC isolation level active when the transaction began (e.g. {@code
 *     READ_COMMITTED}), or {@code UNKNOWN} when it could not be determined
 * @param status {@code COMMITTED}, {@code ROLLED_BACK}, or {@code UNKNOWN} (begin failed, or the
 *     outcome could not be observed)
 * @param startTimestamp epoch milliseconds when the transaction began
 * @param endTimestamp epoch milliseconds when observation ended; a missing completion callback is
 *     described in {@code limitations}, not inferred to be a completed application transaction
 * @param durationMillis wall-clock duration of the observed interval
 * @param parentId id of the transaction active on the same thread when this one began, or {@code
 *     null} for a root transaction
 * @param thread name of the begin callback thread; detached capture does not imply thread ownership
 * @param traceId Micrometer/W3C trace id active when the transaction began, or {@code null} when no
 *     tracer was present
 * @param sqlStatementCount number of SQL Trace executions correlated to this transaction's thread and
 *     time window, or {@code 0} when SQL tracing is unavailable
 * @param connectionCount number of distinct JDBC connections correlated to this transaction, or
 *     {@code 0} when SQL tracing is unavailable
 * @param readOnly whether the transaction was defined as read-only
 * @param slow whether the duration exceeded the configured slow-transaction threshold
 * @param connectionHeld whether the duration exceeded the configured connection-hold threshold,
 *     flagging a transaction that likely held a pooled connection too long; always false for detached capture
 * @param errorMessage populated when {@code status} is {@code UNKNOWN} because starting or completing
 *     the transaction threw
 * @param managerType observed transaction manager class, or {@code UNKNOWN}
 * @param executionKind {@code IMPERATIVE}, {@code REACTIVE}, or {@code UNKNOWN}
 * @param correlationStatus {@code THREAD_TIME_WINDOW} for the JDBC heuristic, {@code NOT_APPLICABLE}
 *     for MongoDB, or {@code UNAVAILABLE}; zero SQL counters are not measured evidence in the latter cases
 * @param limitations missing context or incomplete callback observation; an unknown outcome does not
 *     imply that the application transaction failed
 */
public record TransactionEntryDto(
        long id,
        String methodName,
        String propagation,
        String isolation,
        String status,
        long startTimestamp,
        long endTimestamp,
        long durationMillis,
        Long parentId,
        String thread,
        String traceId,
        int sqlStatementCount,
        int connectionCount,
        boolean readOnly,
        boolean slow,
        boolean connectionHeld,
        String errorMessage,
        String managerType,
        String executionKind,
        String correlationStatus,
        List<String> limitations) {

    public TransactionEntryDto {
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    public TransactionEntryDto(
            long id,
            String methodName,
            String propagation,
            String isolation,
            String status,
            long startTimestamp,
            long endTimestamp,
            long durationMillis,
            Long parentId,
            String thread,
            String traceId,
            int sqlStatementCount,
            int connectionCount,
            boolean readOnly,
            boolean slow,
            boolean connectionHeld,
            String errorMessage) {
        this(
                id,
                methodName,
                propagation,
                isolation,
                status,
                startTimestamp,
                endTimestamp,
                durationMillis,
                parentId,
                thread,
                traceId,
                sqlStatementCount,
                connectionCount,
                readOnly,
                slow,
                connectionHeld,
                errorMessage,
                "UNKNOWN",
                "UNKNOWN",
                "UNAVAILABLE",
                List.of());
    }
}
