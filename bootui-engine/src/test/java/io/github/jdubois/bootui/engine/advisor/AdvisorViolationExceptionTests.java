package io.github.jdubois.bootui.engine.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AdvisorViolationExceptionTests {

    @Test
    void absentIntegerLeavesTheSharedDefaultUnset() {
        assertThat(AdvisorViolationException.parseInteger(null, "offset")).isNull();
        assertThat(AdvisorViolationException.parseInteger(null, "limit")).isNull();
    }

    @ParameterizedTest
    @CsvSource({"0,0", "1,1", "001,1", "1000,1000", "-1,-1", "2147483647,2147483647", "-2147483648,-2147483648"})
    void parsesExactIntegersWithoutApplyingPagingPolicy(String value, int expected) {
        assertThat(AdvisorViolationException.parseInteger(value, "offset")).isEqualTo(expected);
        assertThat(AdvisorViolationException.parseInteger(value, "limit")).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                " ",
                "\t",
                " 1",
                "1 ",
                "1\n",
                "+1",
                "-",
                "--1",
                "1.0",
                "1.5",
                "1e2",
                "0x10",
                "NaN",
                "true",
                "2147483648",
                "-2147483649",
                "9999999999999999999999999",
                "\u0661",
                "password=not-echoed"
            })
    void rejectsMalformedOrOverflowingIntegersWithSafeCanonicalMessages(String value) {
        for (String name : new String[] {"offset", "limit"}) {
            assertThatThrownBy(() -> AdvisorViolationException.parseInteger(value, name))
                    .isInstanceOfSatisfying(AdvisorViolationException.class, failure -> {
                        assertThat(failure.status()).isEqualTo(400);
                        assertThat(failure.getMessage())
                                .isEqualTo("Advisor violation " + name + " must be a valid 32-bit integer.");
                        assertThat(failure.getCause()).isNull();
                    });
        }
    }

    @Test
    void neverEchoesAnUnrecognizedParameterName() {
        assertThatThrownBy(() -> AdvisorViolationException.parseInteger("invalid", "password=not-echoed"))
                .isInstanceOf(AdvisorViolationException.class)
                .hasMessage("Advisor violation parameter must be a valid 32-bit integer.");
        assertThatThrownBy(() -> AdvisorViolationException.parseInteger("invalid", null))
                .isInstanceOf(AdvisorViolationException.class)
                .hasMessage("Advisor violation parameter must be a valid 32-bit integer.");
    }
}
