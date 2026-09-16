package io.github.jdubois.bootui.engine.mongodb;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Startup scope validation shared by adapters. Display limits never rename executable namespaces. */
public final class MongoDbScopes {
    public static final String PREFIX = "bootui.mongodb.clients.";
    public static final String SUFFIX = ".databases";
    public static final int MAX_CLIENTS = 64;
    public static final int MAX_DATABASES = 32;

    private MongoDbScopes() {}

    public static String clientName(String value) {
        if (value == null) throw new IllegalArgumentException("Invalid MongoDB client scope name");
        String name = value;
        if (name.length() > 1 && name.startsWith("\"") && name.endsWith("\"")) {
            name = name.substring(1, name.length() - 1);
        }
        if (name.isBlank()
                || name.length() > 256
                || !name.equals(name.strip())
                || name.indexOf('"') >= 0
                || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid MongoDB client scope name");
        }
        return name;
    }

    public static List<String> parse(String value) {
        if (value == null || value.isBlank() || value.length() > 8192) {
            throw new IllegalArgumentException("Invalid MongoDB configured database scope");
        }
        String[] parts = value.split(",", -1);
        if (parts.length > MAX_DATABASES) {
            throw new IllegalArgumentException("Too many MongoDB configured databases (maximum 32)");
        }
        Set<String> names = new LinkedHashSet<>();
        for (String part : parts) {
            validateName(part);
            if (!names.add(part)) {
                throw new IllegalArgumentException("Duplicate MongoDB configured database name");
            }
        }
        return List.copyOf(names);
    }

    public static String validateName(String name) {
        // MongoDB database names are less than 64 UTF-8 bytes. Whitespace is rejected, not trimmed.
        if (name == null
                || name.isBlank()
                || name.length() > 63
                || !name.equals(name.strip())
                || name.getBytes(StandardCharsets.UTF_8).length > 63
                || name.chars()
                        .anyMatch(character -> Character.isISOControl(character) || Character.isWhitespace(character))
                || name.matches(".*[/\\\\. \"$*<>:|?].*")) {
            throw new IllegalArgumentException("Invalid MongoDB configured database scope");
        }
        return name;
    }
}
