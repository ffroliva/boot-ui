package io.github.jdubois.bootui.engine.advisor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Scan-confined collection of counted findings with one shared retention budget.
 * Callers supply their advisor's bounded text formatting and redaction before anything is retained.
 */
public final class AdvisorViolationCollector {

    private final int retentionLimit;
    private final Map<String, MutableRule> rules = new LinkedHashMap<>();
    private int total;
    private int retained;

    public AdvisorViolationCollector(int retentionLimit) {
        if (retentionLimit <= 0) {
            throw new IllegalArgumentException("Advisor violation retention limit must be positive.");
        }
        this.retentionLimit = retentionLimit;
    }

    /**
     * Returns the unused scan budget without reserving entries.
     * A rule can stage at most this many details and record them only after successful evaluation.
     */
    public int remainingCapacity() {
        return retentionLimit - retained;
    }

    /**
     * Adds a rule's original count and ordered details, before summary sampling or dismissal.
     * Repeated calls for a rule accumulate (for example, across Hibernate persistence units).
     * Missing details still contribute to the count and are reported as incomplete retention.
     */
    public void record(String ruleId, int violationCount, List<String> details, UnaryOperator<String> sanitizer) {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("Advisor rule ID must not be blank.");
        }
        if (violationCount < 0) {
            throw new IllegalArgumentException("Advisor violation count must not be negative.");
        }
        Objects.requireNonNull(sanitizer, "Advisor violation sanitizer is required.");
        if (violationCount == 0) {
            return;
        }

        int updatedTotal = Math.addExact(total, violationCount);
        MutableRule existing = rules.get(ruleId);
        int updatedCount = Math.addExact(existing == null ? 0 : existing.count, violationCount);
        int available = Math.min(violationCount, remainingCapacity());
        int toRetain = details == null ? 0 : Math.min(details.size(), available);
        List<String> sanitized = new ArrayList<>(toRetain);
        if (toRetain > 0) {
            for (String detail : details) {
                sanitized.add(Objects.requireNonNull(
                        sanitizer.apply(detail), "Advisor violation sanitizer must return non-null text."));
                if (sanitized.size() == toRetain) {
                    break;
                }
            }
        }

        MutableRule rule = rules.computeIfAbsent(ruleId, ignored -> new MutableRule());
        rule.details.addAll(sanitized);
        rule.count = updatedCount;
        total = updatedTotal;
        retained += sanitized.size();
    }

    Snapshot snapshot() {
        Map<String, Rule> copy = new LinkedHashMap<>();
        rules.forEach((id, rule) -> copy.put(id, new Rule(rule.count, List.copyOf(rule.details))));
        return new Snapshot(total, retained, retentionLimit, Collections.unmodifiableMap(copy));
    }

    record Rule(int violationCount, List<String> details) {}

    record Snapshot(int total, int retained, int retentionLimit, Map<String, Rule> rules) {}

    private static final class MutableRule {
        private int count;
        private final List<String> details = new ArrayList<>();
    }
}
