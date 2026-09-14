package io.github.jdubois.bootui.engine.postgres;

/** Immutable per-datasource row limits, configured when the PostgreSQL service is created. */
public record PostgresRowLimits(
        int maxSessions,
        int maxStatements,
        int maxIndexes,
        int maxTables,
        int maxVacuumTables,
        int maxReplicas,
        int maxSettings) {

    public PostgresRowLimits {
        requireValid("bootui.postgresql.max-sessions", maxSessions);
        requireValid("bootui.postgresql.max-statements", maxStatements);
        requireValid("bootui.postgresql.max-indexes", maxIndexes);
        requireValid("bootui.postgresql.max-tables", maxTables);
        requireValid("bootui.postgresql.max-vacuum-tables", maxVacuumTables);
        requireValid("bootui.postgresql.max-replicas", maxReplicas);
        requireValid("bootui.postgresql.max-settings", maxSettings);
    }

    public static PostgresRowLimits defaults() {
        return new PostgresRowLimits(100, 100, 500, 200, 200, 10, 40);
    }

    /** Shared with adapter property binding; leaves room for the extra row used to detect truncation. */
    public static int requireValid(String property, int value) {
        if (value <= 0 || value == Integer.MAX_VALUE) {
            throw new IllegalArgumentException(property + " must be between 1 and 2147483646.");
        }
        return value;
    }
}
