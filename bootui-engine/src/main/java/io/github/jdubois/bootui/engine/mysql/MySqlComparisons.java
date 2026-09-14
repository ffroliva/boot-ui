package io.github.jdubois.bootui.engine.mysql;

import io.github.jdubois.bootui.core.dto.MySqlChangeDto;
import io.github.jdubois.bootui.core.dto.MySqlDataSourceDto;
import io.github.jdubois.bootui.core.dto.MySqlMetricDto;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded server-counter baselines only; gauges, unavailable values and incomparable resets are excluded. */
final class MySqlComparisons {
    private static final Set<String> COUNTERS = Set.of(
            "Connections",
            "Aborted_connects",
            "Innodb_buffer_pool_reads",
            "Innodb_buffer_pool_read_requests",
            "Innodb_buffer_pool_wait_free",
            "Innodb_row_lock_waits",
            "Innodb_row_lock_time",
            "Innodb_log_waits");
    private final Map<String, Baseline> baselines = new HashMap<>();

    List<MySqlChangeDto> observe(String key, String server, MySqlDataSourceDto report) {
        if (server == null || report.readAt() == null) {
            return List.of();
        }
        long now = report.readAt();
        String uptimeText = report.vitalSigns().stream()
                .filter(metric -> "Uptime".equals(metric.id()))
                .map(MySqlMetricDto::value)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (uptimeText == null) {
            return List.of();
        }
        BigInteger uptime = new BigInteger(uptimeText);
        BigInteger bootTime = BigInteger.valueOf(now).subtract(uptime.multiply(BigInteger.valueOf(1000)));
        Baseline baseline = baselines.get(key);
        String identity = server + "\u0000" + report.schemaName();
        boolean reset = baseline == null
                || !identity.equals(baseline.server)
                || uptime.compareTo(baseline.uptime) < 0
                || bootTime.subtract(baseline.bootTime).abs().compareTo(BigInteger.valueOf(3000)) > 0;
        if (!reset) {
            for (MySqlMetricDto metric : report.vitalSigns()) {
                Observation old = baseline.metrics.get(metric.id());
                if (old != null
                        && metric.value() != null
                        && COUNTERS.contains(metric.id())
                        && new BigInteger(metric.value()).compareTo(old.value) < 0) {
                    reset = true;
                    break;
                }
            }
        }
        if (reset) {
            baseline = new Baseline(identity, uptime, bootTime);
            baselines.put(key, baseline);
        }
        List<MySqlChangeDto> changes = new ArrayList<>();
        for (MySqlMetricDto metric : report.vitalSigns()) {
            if (!COUNTERS.contains(metric.id()) || metric.value() == null) {
                continue;
            }
            BigInteger current = new BigInteger(metric.value());
            Observation old = baseline.metrics.get(metric.id());
            if (old != null && now > old.at) {
                changes.add(new MySqlChangeDto(
                        metric.id(),
                        metric.scope(),
                        metric.unit(),
                        current.subtract(old.value).toString(),
                        old.at,
                        now,
                        "Same observed server and no detected restart/counter decrease; independent resets"
                                + " remain possible."));
            }
            baseline.metrics.put(metric.id(), new Observation(current, now));
        }
        baseline.uptime = uptime;
        return List.copyOf(changes);
    }

    void retain(Set<String> keys) {
        baselines.keySet().retainAll(keys);
    }

    void clear() {
        baselines.clear();
    }

    private static final class Baseline {
        final String server;
        BigInteger uptime;
        final BigInteger bootTime;
        final Map<String, Observation> metrics = new HashMap<>();

        Baseline(String server, BigInteger uptime, BigInteger bootTime) {
            this.server = server;
            this.uptime = uptime;
            this.bootTime = bootTime;
        }
    }

    private record Observation(BigInteger value, long at) {}
}
