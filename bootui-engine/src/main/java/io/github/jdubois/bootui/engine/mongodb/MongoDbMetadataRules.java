package io.github.jdubois.bootui.engine.mongodb;

/** Shared interpretation of driver-neutral metadata tokens and server error numbers. */
public final class MongoDbMetadataRules {
    private MongoDbMetadataRules() {}

    public static String indexKind(String field, String value) {
        if (field != null && (field.equals("$**") || field.endsWith(".$**"))) return "WILDCARD";
        if (value == null) return "UNKNOWN";
        return switch (value) {
            case "1", "1.0", "ASC" -> "ASC";
            case "-1", "-1.0", "DESC" -> "DESC";
            case "hashed", "HASHED" -> "HASHED";
            case "text", "TEXT" -> "TEXT";
            case "2d", "GEO_2D" -> "GEO_2D";
            case "2dsphere", "GEO_2DSPHERE" -> "GEO_2DSPHERE";
            case "geoHaystack", "GEO_HAYSTACK" -> "GEO_HAYSTACK";
            case "WILDCARD" -> "WILDCARD";
            default -> "UNKNOWN";
        };
    }

    public static String serverError(int code) {
        return switch (code) {
            case 13, 18 -> "DENIED";
            case 26 -> "NAMESPACE_DISAPPEARED";
            case 50, 89, 262 -> "TIMEOUT";
            case 59, 115, 303 -> "UNSUPPORTED";
            default -> "FAILED";
        };
    }
}
