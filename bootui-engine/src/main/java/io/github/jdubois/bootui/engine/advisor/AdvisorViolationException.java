package io.github.jdubois.bootui.engine.advisor;

/**
 * Framework-neutral failure for a retained advisor-detail read.
 * Messages contain no supplied identifiers or finding text and are safe for transport responses.
 */
public final class AdvisorViolationException extends RuntimeException {

    private final int status;

    public AdvisorViolationException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    /**
     * Parses an optional REST paging parameter without framework-specific coercion.
     * Range policy is applied by {@link AdvisorScanState#ruleViolations}.
     */
    public static Integer parseInteger(String raw, String name) {
        if (raw == null) {
            return null;
        }
        String parameter = "offset".equals(name) ? "offset" : "limit".equals(name) ? "limit" : "parameter";
        String message = "Advisor violation " + parameter + " must be a valid 32-bit integer.";
        if (!raw.matches("-?[0-9]+")) {
            throw new AdvisorViolationException(400, message);
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException exception) {
            throw new AdvisorViolationException(400, message);
        }
    }
}
