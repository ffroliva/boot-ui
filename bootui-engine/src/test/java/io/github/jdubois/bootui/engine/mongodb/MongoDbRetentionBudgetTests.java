package io.github.jdubois.bootui.engine.mongodb;

import static org.assertj.core.api.Assertions.*;

import io.github.jdubois.bootui.core.dto.MongoDbIndexKeyDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class MongoDbRetentionBudgetTests {
    @Test
    void countAndEscapedByteLimitsAreIndependent() {
        MongoDbRetentionBudget items =
                new MongoDbRetentionBudget(MongoDbSettings.from(key -> key.endsWith("max-total-items") ? "2" : null));
        assertThat(items.retain(List.of("one", "two"))).isTrue();
        assertThat(items.retain("three")).isFalse();
        assertThat(items.exhausted()).isEqualTo("ITEM_LIMIT");
        MongoDbRetentionBudget bytes = new MongoDbRetentionBudget(MongoDbSettings.defaults());
        assertThat(bytes.retain("\u0000".repeat(100000))).isFalse();
        assertThat(bytes.exhausted()).isEqualTo("BYTE_LIMIT");
        assertThat(MongoDbRetentionBudget.bytes(new MongoDbIndexKeyDto("x", "ASC")))
                .isGreaterThanOrEqualTo(
                        "{\"field\":\"x\",\"kind\":\"ASC\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertThat(MongoDbRetentionBudget.bytes("\u0000")).isEqualTo("\"\\u0000\"".length());
        assertThat(MongoDbRetentionBudget.bytes("\u00e9")).isEqualTo("\"\\u00e9\"".length());
        assertThat(MongoDbRetentionBudget.bytes("\"\\")).isEqualTo("\"\\\"\\\\\"".length());
    }
}
