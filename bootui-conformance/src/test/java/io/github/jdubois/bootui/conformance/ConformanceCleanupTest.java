package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ConformanceCleanupTest {

    @Test
    void retainsTheContractFailureAndSuppressesCleanupFailure() {
        AssertionError primary = new AssertionError("report parity failed");
        AssertionError secondary = new AssertionError("restore failed");

        assertThatThrownBy(() -> {
                    try (var cleanup = new ConformanceCleanup(() -> {
                        throw secondary;
                    })) {
                        throw primary;
                    }
                })
                .isSameAs(primary);
        assertThat(primary.getSuppressed()).containsExactly(secondary);
    }

    @Test
    void reportsCleanupFailureWhenTheContractSucceeded() {
        AssertionError failure = new AssertionError("restore failed");

        assertThatThrownBy(() -> {
                    try (var cleanup = new ConformanceCleanup(() -> {
                        throw failure;
                    })) {
                        // The contract body completed successfully.
                    }
                })
                .isSameAs(failure);
    }
}
