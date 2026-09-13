package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for the local Hibernate Advisor panel. The results list contains violating
 * checks only, ordered by severity and impact.
 */
public record HibernateReport(
        boolean localOnly,
        String disclaimer,
        List<String> entityPackages,
        int entitiesAnalyzed,
        int rulesEvaluated,
        int violationsFound,
        List<HibernateSeverityCountDto> severityCounts,
        HibernateScanStatusDto scan,
        List<HibernateRuleResultDto> results,
        AdvisorEvidenceDto evidence,
        AdvisorViolationDetailsDto violationDetails) {

    public HibernateReport {
        evidence = evidence == null ? AdvisorEvidenceDto.unknown() : evidence;
        violationDetails = violationDetails == null ? AdvisorViolationDetailsDto.unknown() : violationDetails;
        entityPackages = DtoCollections.immutableCopy(entityPackages);
        severityCounts = DtoCollections.immutableCopy(severityCounts);
        results = DtoCollections.immutableCopy(results);
    }

    public HibernateReport(
            boolean localOnly,
            String disclaimer,
            List<String> entityPackages,
            int entitiesAnalyzed,
            int rulesEvaluated,
            int violationsFound,
            List<HibernateSeverityCountDto> severityCounts,
            HibernateScanStatusDto scan,
            List<HibernateRuleResultDto> results,
            AdvisorEvidenceDto evidence) {
        this(
                localOnly,
                disclaimer,
                entityPackages,
                entitiesAnalyzed,
                rulesEvaluated,
                violationsFound,
                severityCounts,
                scan,
                results,
                evidence,
                null);
    }

    public HibernateReport withViolationDetails(AdvisorViolationDetailsDto violationDetails) {
        return new HibernateReport(
                localOnly,
                disclaimer,
                entityPackages,
                entitiesAnalyzed,
                rulesEvaluated,
                violationsFound,
                severityCounts,
                scan,
                results,
                evidence,
                violationDetails);
    }
}
