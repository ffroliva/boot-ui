package io.github.jdubois.bootui.engine.reactivesecurity;

import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
import io.github.jdubois.bootui.core.dto.SecurityReport;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import java.util.function.IntSupplier;

/** Shared stateful facade used by the WebFlux HTTP and MCP adapters. */
public final class ReactiveSecurityAdvisorService {

    private final ReactiveSecurityScanner scanner;
    private final DismissedRulesStore dismissedRules;

    public ReactiveSecurityAdvisorService(ReactiveSecurityScanner scanner, DismissedRulesStore dismissedRules) {
        this.scanner = scanner;
        this.dismissedRules = dismissedRules;
    }

    public SecurityReport report() {
        return lastReport();
    }

    public SecurityReport lastReport() {
        return scanner.applyDismissals(scanner.lastReport(), dismissedRules.load());
    }

    public AdvisorRuleViolationsDto ruleViolations(String ruleId, String scanId, Integer offset, Integer limit) {
        return scanner.ruleViolations(ruleId, scanId, offset, limit);
    }

    public void setViolationRetentionLimit(IntSupplier limit) {
        scanner.setViolationRetentionLimit(limit);
    }

    public SecurityReport scan() {
        SecurityReport report = scanner.scan();
        return scanner.applyDismissals(report, dismissedRules.load());
    }
}
