package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Explicit live-lane executable checks. Missing CLI/browser prerequisites are failures, never skips. */
public final class MongoDbProcessContract {
    private MongoDbProcessContract() {}

    public static Path root() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("bootui-engine"))) root = root.getParent();
        if (root == null) throw new AssertionError("BootUI repository root not found");
        return root;
    }

    public static String cli(String origin, String api, String clientId) {
        try (var jars = Files.list(root().resolve("bootui-cli/target"))) {
            List<Path> candidates = jars.filter(
                            path -> path.getFileName().toString().endsWith("-all.jar"))
                    .toList();
            assertThat(candidates)
                    .as("Build exactly one current executable CLI before the required live lane")
                    .hasSize(1);
            List<String> command = new ArrayList<>(List.of(
                    Path.of(System.getProperty("java.home"), "bin/java").toString(),
                    "-jar",
                    candidates.get(0).toString(),
                    "db",
                    "mongodb",
                    "inspect",
                    "--client-id",
                    clientId,
                    "--scope",
                    "CONFIGURED",
                    "--url",
                    origin,
                    "--api-path",
                    api,
                    "--json"));
            String result = run(command, Duration.ofSeconds(45));
            assertThat(result)
                    .contains("\"snapshotId\"", "\"collectionsRetained\"")
                    .doesNotContain("mongodb://");
            var report = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result);
            assertThat(report.path("status").asText()).isIn("READ", "PARTIAL");
            assertThat(report.at("/inspection/collectionsRetained").asInt()).isPositive();
            assertThat(report.at("/inspection/indexesRetained").asInt()).isPositive();
            return result;
        } catch (java.io.IOException ex) {
            throw new AssertionError("Cannot locate current CLI artifact", ex);
        }
    }

    public static void browser(String origin, String ui, boolean inspect) {
        run(
                List.of(
                        "node",
                        root().resolve("bootui-spring-sample-app/e2e/scripts/mongodb-live.mjs")
                                .toString(),
                        origin,
                        ui,
                        inspect ? "inspect" : "passive"),
                Duration.ofSeconds(90));
    }

    private static String run(List<String> command, Duration timeout) {
        Process process = null;
        Path output = null;
        try {
            output = Files.createTempFile("bootui-mongodb-process-", ".log");
            process = new ProcessBuilder(command)
                    .directory(root().toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start();
            assertThat(process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS))
                    .as("Live process deadline")
                    .isTrue();
            assertThat(Files.size(output)).isLessThan(4 * 1024 * 1024);
            String body = Files.readString(output);
            assertThat(process.exitValue()).as(body).isZero();
            return body;
        } catch (java.io.IOException ex) {
            throw new AssertionError("Live executable prerequisite failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Live executable interrupted", ex);
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (java.io.IOException ignored) {
                }
            }
        }
    }
}
