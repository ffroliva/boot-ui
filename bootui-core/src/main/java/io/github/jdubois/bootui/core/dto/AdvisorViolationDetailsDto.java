package io.github.jdubois.bootui.core.dto;

/**
 * Retrieval completeness for the concrete findings in one completed advisor scan.
 * This is independent of assessment evidence, coverage, and dismissal.
 */
public record AdvisorViolationDetailsDto(
        String scanId, int total, int retained, int retentionLimit, boolean truncated) {

    public static AdvisorViolationDetailsDto unknown() {
        return new AdvisorViolationDetailsDto(null, 0, 0, 0, false);
    }
}
