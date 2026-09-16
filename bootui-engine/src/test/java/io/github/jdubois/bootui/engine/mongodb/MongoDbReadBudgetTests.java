package io.github.jdubois.bootui.engine.mongodb;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class MongoDbReadBudgetTests {
    @Test
    void deadlineNeverRoundsToUnlimitedZero() {
        AtomicLong clock = new AtomicLong();
        MongoDbReadBudget budget = new MongoDbReadBudget(10, 4, clock::get);
        assertThat(budget.operationMillis()).isEqualTo(4);
        clock.set(8_000_000);
        assertThat(budget.operationMillis()).isEqualTo(2);
        clock.set(9_500_000);
        assertThatThrownBy(budget::operationMillis)
                .isInstanceOf(MongoDbReadException.class)
                .hasMessage("TIMEOUT");
    }

    @Test
    void invalidSettingsFailClosed() {
        assertThatThrownBy(() -> MongoDbSettings.from(key -> key.endsWith("max-clients") ? "0" : null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MongoDbSettings.from(key -> key.endsWith("inspect-enabled") ? "" : null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MongoDbSettings.from(key -> key.endsWith("timeout-ms") ? "2147483648" : null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
