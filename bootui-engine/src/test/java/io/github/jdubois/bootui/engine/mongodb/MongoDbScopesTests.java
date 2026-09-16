package io.github.jdubois.bootui.engine.mongodb;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class MongoDbScopesTests {
    @Test
    void preservesExactNamesAndAllowsDeclarationsBeyondTheInspectionCap() {
        assertThat(MongoDbScopes.parse("orders,audit")).containsExactly("orders", "audit");
        assertThat(MongoDbScopes.parse(
                        IntStream.range(0, 32).mapToObj(i -> "db" + i).collect(Collectors.joining(","))))
                .hasSize(32);
        assertThat(MongoDbScopes.clientName("\"order.store\"")).isEqualTo("order.store");
        assertThat(MongoDbScopes.parse("a".repeat(63))).containsExactly("a".repeat(63));
    }

    @Test
    void rejectsInvalidDuplicateOrRenamedNamespacesAndBoundsUtf8Bytes() {
        for (String invalid : List.of(
                "",
                " ",
                "one,",
                "one,one",
                " one",
                "one ",
                "a b",
                "a.b",
                "a/b",
                "a\\b",
                "a\u0000b",
                "a\nb",
                "a\"b",
                "a$b",
                "a*b",
                "a<b",
                "a>b",
                "a:b",
                "a|b",
                "a?b",
                "a".repeat(64),
                "\u00e9".repeat(32),
                "x".repeat(8193),
                IntStream.range(0, 33).mapToObj(i -> "db" + i).collect(Collectors.joining(",")))) {
            assertThatThrownBy(() -> MongoDbScopes.parse(invalid))
                    .as("scope %s", invalid)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> MongoDbScopes.parse(null)).isInstanceOf(IllegalArgumentException.class);
        for (String invalid :
                List.of("", "\"\"", " client", "client ", "\"unterminated", "a\"b", "a\nb", "x".repeat(257))) {
            assertThatThrownBy(() -> MongoDbScopes.clientName(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
