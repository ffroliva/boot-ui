package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One page of retained, sanitized details for a finding, including dismissed findings.
 * Page totals describe retained entries; {@code violationCount} describes all counted findings.
 */
public record AdvisorRuleViolationsDto(
        String scanId,
        String ruleId,
        int violationCount,
        int retainedCount,
        boolean truncated,
        List<String> violations,
        PageMetadata page) {

    public AdvisorRuleViolationsDto {
        violations = DtoCollections.immutableCopy(violations);
    }
}
