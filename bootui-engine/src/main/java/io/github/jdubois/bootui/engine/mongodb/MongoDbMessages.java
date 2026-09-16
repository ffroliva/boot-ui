package io.github.jdubois.bootui.engine.mongodb;

import java.util.Set;

/** The shared human-facing vocabulary for adapter limitations and expected metadata failures. */
public final class MongoDbMessages {
    private static final Set<String> TRUNCATION = Set.of(
            "ITEM_LIMIT",
            "BYTE_LIMIT",
            "TEXT_LIMIT",
            "INDEX_KEY_LIMIT",
            "TOPOLOGY_LIMIT",
            "Index key limit reached.",
            "Topology server limit reached.");

    private MongoDbMessages() {}

    public static boolean truncated(String value) {
        return value != null && TRUNCATION.contains(value);
    }

    public static String limitation(String value) {
        if (value == null) return "Metadata could not be observed.";
        return switch (value) {
            case "NO_CLIENT" -> "No managed MongoDB client is declared; add the framework's MongoDB integration.";
            case "NOT_INITIALIZED" -> "Client is not an initialized singleton; BootUI will not initialize it.";
            case "INACTIVE" -> "This application client is inactive; BootUI will not activate it.";
            case "REMOVED" -> "The framework removed this unused client declaration; no client was initialized.";
            case "UNKNOWN_BINDING" -> "Custom or proxy client binding is not inspected.";
            case "DISCOVERY_FAILED" -> "Local client metadata could not be discovered; exception details are withheld.";
            case "DRIVER_VERSION_UNAVAILABLE" ->
                "The existing driver does not expose its version through package metadata.";
            case "NO_CONFIGURED_DATABASE" -> "No verified database binding; configure this client's database scope.";
            case "ITEM_LIMIT" -> "A retained metadata item limit was reached; additional declarations were omitted.";
            case "BYTE_LIMIT" -> "The retained metadata byte limit was reached; additional metadata was omitted.";
            case "TEXT_LIMIT" -> "Some metadata text was shortened to the configured display limit.";
            case "INDEX_KEY_LIMIT", "Index key limit reached." ->
                "Additional index keys were omitted by the metadata limit.";
            case "TOPOLOGY_LIMIT", "Topology server limit reached." ->
                "Additional topology servers were omitted by the metadata limit.";
            default -> value;
        };
    }

    public static String failure(String code) {
        return switch (code) {
            case "DENIED" -> "The application's account cannot read this metadata.";
            case "AUTHENTICATION_FAILED" -> "Application client authentication failed; credentials are never returned.";
            case "TIMEOUT" -> "The metadata operation or whole inspection budget expired.";
            case "CANCELLED" -> "Inspection was cancelled; no automatic retry.";
            case "UNSUPPORTED" -> "This metadata capability is not supported for the selected target.";
            case "NAMESPACE_DISAPPEARED" -> "The collection disappeared during the observation interval.";
            case "CLEANUP_FAILED" ->
                "An owned cursor or subscription could not be closed cleanly; the primary failure is retained.";
            default -> "Metadata could not be read; driver exception details are withheld.";
        };
    }
}
