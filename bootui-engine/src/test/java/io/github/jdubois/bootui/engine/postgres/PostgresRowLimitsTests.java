package io.github.jdubois.bootui.engine.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PostgresRowLimitsTests {

    @Test
    void defaultsIncreaseRowCoverageWithoutRelaxingTimeOrTextBounds() {
        assertThat(PostgresRowLimits.defaults()).isEqualTo(new PostgresRowLimits(100, 100, 500, 200, 200, 10, 40));
        assertThat(PostgresInsightLimits.DEFAULTS)
                .isEqualTo(new PostgresInsightLimits(
                        100,
                        100,
                        500,
                        200,
                        200,
                        10,
                        40,
                        400,
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(2)));
    }

    @Test
    void customRowLimitsDoNotChangeTimeOrTextBounds() {
        assertThat(PostgresInsightLimits.withRowLimits(new PostgresRowLimits(1, 2, 3, 4, 5, 6, 7)))
                .isEqualTo(new PostgresInsightLimits(
                        1,
                        2,
                        3,
                        4,
                        5,
                        6,
                        7,
                        400,
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(2)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void invalidBoundsNameThePropertyRatherThanClampingOrOverflowing(int invalid) {
        List<String> names = List.of(
                "max-sessions",
                "max-statements",
                "max-indexes",
                "max-tables",
                "max-vacuum-tables",
                "max-replicas",
                "max-settings");
        for (int index = 0; index < names.size(); index++) {
            int[] limits = {1, 2, 3, 4, 5, 6, 7};
            limits[index] = invalid;
            assertThatThrownBy(() -> new PostgresRowLimits(
                            limits[0], limits[1], limits[2], limits[3], limits[4], limits[5], limits[6]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("bootui.postgresql." + names.get(index) + " must be between 1 and 2147483646.");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, Integer.MAX_VALUE - 1})
    void validBoundaryValuesAreNotSilentlyChanged(int value) {
        PostgresRowLimits limits = new PostgresRowLimits(value, value, value, value, value, value, value);
        assertThat(limits)
                .extracting(
                        PostgresRowLimits::maxSessions,
                        PostgresRowLimits::maxStatements,
                        PostgresRowLimits::maxIndexes,
                        PostgresRowLimits::maxTables,
                        PostgresRowLimits::maxVacuumTables,
                        PostgresRowLimits::maxReplicas,
                        PostgresRowLimits::maxSettings)
                .containsExactly(value, value, value, value, value, value, value);
    }
}
