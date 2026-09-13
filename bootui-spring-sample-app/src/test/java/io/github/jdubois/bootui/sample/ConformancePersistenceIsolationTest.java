package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

class ConformancePersistenceIsolationTest {

    @Test
    void concurrentConformanceSuitesUseDistinctDismissalFiles() {
        String propertyPrefix = "bootui.overrides-file=";
        List<Path> dismissalFiles = Stream.of(
                        SpringApiConformanceTest.class,
                        SpringMcpConformanceTest.class,
                        SpringCliConformanceTest.class,
                        BootUiCustomPathIntegrationTests.class)
                .flatMap(test ->
                        Arrays.stream(test.getAnnotation(SpringBootTest.class).properties()))
                .filter(property -> property.startsWith(propertyPrefix))
                .map(property -> Path.of(property.substring(propertyPrefix.length()))
                        .toAbsolutePath()
                        .normalize()
                        .resolveSibling("boot-ui.yml"))
                .toList();

        assertThat(dismissalFiles).hasSize(4).doesNotHaveDuplicates();
    }
}
