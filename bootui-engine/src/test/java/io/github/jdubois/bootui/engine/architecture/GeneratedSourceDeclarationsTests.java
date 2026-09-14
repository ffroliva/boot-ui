package io.github.jdubois.bootui.engine.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GeneratedSourceDeclarationsTests {
    @Test
    void ignoresCommentsLiteralsAndNestedDeclarations() throws IOException {
        var result = GeneratedSourceDeclarations.read("""
                // package wrong; class Invented {}
                package sample.api;
                @jakarta.annotation.Generated("class Fake {}")
                public class ApiUtil {
                    String example = "package wrong; class Bogus {}";
                    class Nested {}
                }
                record Other(String text) {}
                """);
        assertThat(result.packageName()).isEqualTo("sample.api");
        assertThat(result.typeNames()).containsExactlyInAnyOrder("ApiUtil", "Other");
    }

    @Test
    void recognizesKotlinPackagesObjectsAndEscapedNames() throws IOException {
        var result = GeneratedSourceDeclarations.read("""
                package sample.`kotlin-api`
                /* outer /* nested */ comment */
                object ApiUtil {
                    fun run() { throw RuntimeException("example") }
                }
                enum class Status { READY }
                class Container {
                    companion object { fun call() = ApiUtil.run() }
                }
                """, true);
        assertThat(result.packageName()).isEqualTo("sample.kotlin-api");
        assertThat(result.typeNames()).containsExactlyInAnyOrder("ApiUtil", "Status", "Container");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "package sample; class ApiUtil {",
                "/* unterminated class ApiUtil {}",
                "package sample; class ApiUtil { String text = \"unterminated; }",
                "package sample; package other; class ApiUtil {}",
                "package sample; // \\u00xz class ApiUtil {}"
            })
    void unsupportedOrIncompleteSourceNeverBecomesPositiveOwnership(String source) {
        assertThatThrownBy(() -> GeneratedSourceDeclarations.read(source)).isInstanceOf(IOException.class);
    }

    @Test
    void stringTemplatesAndRawStringsCannotInventDeclarations() throws IOException {
        var result = GeneratedSourceDeclarations.read("""
                package sample
                val value = "${listOf("}", "class Fake {}", "${42}").joinToString()}"
                val raw = \"""class Bogus {}
                    ${"nested expression"}
                \"""
                object ApiUtil { val text = "\\\\u0000" }
                """, true);
        assertThat(result.typeNames()).containsExactly("ApiUtil");
    }

    @Test
    void javaCommentsDoNotNestAndTextBlockEscapesStayInsideTheLiteral() throws IOException {
        var result = GeneratedSourceDeclarations.read("""
                package sample;
                /* nested opener is only text: /* */ class ApiUtil {
                    String text = \"""
                        \\\""" class Fake {}
                        \""";
                }
                """);
        assertThat(result.typeNames()).containsExactly("ApiUtil");
    }

    @Test
    void whitespaceAndMultilineCommentsDoNotHideDeclarationNames() throws IOException {
        for (boolean kotlin : new boolean[] {false, true}) {
            var result = GeneratedSourceDeclarations.read("""
                    package sample;
                    class
                    ApiUtil {}
                    interface /* multiline
                    comment */ Service {}
                    """, kotlin);
            assertThat(result.typeNames()).containsExactlyInAnyOrder("ApiUtil", "Service");
        }
    }

    @Test
    void translatesJavaUnicodeBeforeRecognizingCommentsPackagesAndTypes() throws IOException {
        String escape = "\\" + "u";
        var result = GeneratedSourceDeclarations.read("package s" + escape + "0061mple; // comment"
                + escape + "000d public cl" + escape + "0061ss Api" + escape + "0000Util "
                + escape + "007b String text = \"" + escape + "2026\"; " + escape + "007d");
        assertThat(result.packageName()).isEqualTo("sample");
        assertThat(result.typeNames()).containsExactly("ApiUtil");
    }

    @Test
    void unicodeEligibilityUsesTranslatedBackslashParityWithoutRecursiveExpansion() throws IOException {
        String slash = "\\";
        assertThat(GeneratedSourceDeclarations.translateJavaUnicode(slash + "uuuu005a"))
                .isEqualTo("Z");
        assertThat(GeneratedSourceDeclarations.translateJavaUnicode(slash.repeat(2) + "u005a"))
                .isEqualTo(slash.repeat(2) + "u005a");
        assertThat(GeneratedSourceDeclarations.translateJavaUnicode(slash.repeat(3) + "u005a"))
                .isEqualTo(slash.repeat(2) + "Z");
        assertThat(GeneratedSourceDeclarations.translateJavaUnicode(slash + "u005cu005a"))
                .isEqualTo(slash + "u005a");
        assertThat(GeneratedSourceDeclarations.translateJavaUnicode(slash + "u005c" + slash + "u005a"))
                .isEqualTo(slash + "Z");
        assertThat(GeneratedSourceDeclarations.translateJavaUnicode(slash + "u005c" + slash.repeat(2) + "u005a"))
                .isEqualTo(slash.repeat(2) + "Z");
        assertThatThrownBy(() -> GeneratedSourceDeclarations.translateJavaUnicode(slash + "u0"))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> GeneratedSourceDeclarations.translateJavaUnicode(slash + "u0xz0"))
                .isInstanceOf(IOException.class);
    }
}
