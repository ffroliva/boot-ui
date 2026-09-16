package io.github.jdubois.bootui.quarkus.mongodb;

import io.github.jdubois.bootui.engine.mongodb.MongoDbScopes;
import io.github.jdubois.bootui.engine.mongodb.MongoDbSettings;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.eclipse.microprofile.config.Config;

/** Startup-bound database scope, independent of connection strings and optional driver types. */
public final class MongoDbClientDeclarations {
    private final Map<String, List<String>> scopes;
    private final Map<String, String> inferredDatabases;
    private final Set<String> inactive;

    public MongoDbClientDeclarations(Config config, MongoDbSettings settings) {
        Map<String, List<String>> configured = new TreeMap<>();
        Map<String, String> inferred = new TreeMap<>();
        Set<String> inactive = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String property : config.getPropertyNames()) {
            if (!property.startsWith(MongoDbScopes.PREFIX) && !property.startsWith("quarkus.mongodb.")) continue;
            if (!seen.add(property)) continue;
            if (seen.size() > 4096) throw new IllegalArgumentException("Too many MongoDB configuration entries");
            String prefix = MongoDbScopes.PREFIX;
            if (property.startsWith(prefix) && property.endsWith(".databases")) {
                String name = MongoDbScopes.clientName(
                        property.substring(prefix.length(), property.length() - MongoDbScopes.SUFFIX.length()));
                List<String> databases =
                        parseDatabases(config.getConfigValue(property).getValue(), settings);
                if (configured.putIfAbsent(name, databases) != null) {
                    throw new IllegalArgumentException("Duplicate MongoDB database scope");
                }
            }
            String nativePrefix = "quarkus.mongodb.";
            if (property.startsWith(nativePrefix)) {
                String suffix = property.substring(nativePrefix.length());
                if (suffix.equals("database") || suffix.endsWith(".database")) {
                    String name = suffix.equals("database")
                            ? "default"
                            : unquote(suffix.substring(0, suffix.length() - ".database".length()));
                    String value = config.getConfigValue(property).getValue();
                    if (value != null) {
                        inferred.put(MongoDbScopes.clientName(name), MongoDbScopes.validateName(value));
                    }
                } else if (suffix.equals("active") || suffix.endsWith(".active")) {
                    String name = suffix.equals("active")
                            ? "default"
                            : unquote(suffix.substring(0, suffix.length() - ".active".length()));
                    if ("false".equalsIgnoreCase(config.getConfigValue(property).getValue())) {
                        inactive.add(MongoDbScopes.clientName(name));
                    }
                }
            }
            if (configured.size() > 64 || inferred.size() > 64 || inactive.size() > 64) {
                throw new IllegalArgumentException("MongoDB configuration discovery exceeded its local bound");
            }
        }
        this.scopes = Map.copyOf(configured);
        this.inferredDatabases = Map.copyOf(inferred);
        this.inactive = Set.copyOf(inactive);
    }

    public List<String> databases(String name) {
        List<String> explicit = scopes.get(name);
        if (explicit != null) return explicit;
        String value = inferredDatabases.get(name);
        return value == null ? List.of() : List.of(value);
    }

    public boolean inactive(String name) {
        return inactive.contains(name);
    }

    private static String unquote(String name) {
        if (name.length() > 1 && name.startsWith("\"") && name.endsWith("\"")) {
            return name.substring(1, name.length() - 1);
        }
        return name;
    }

    static List<String> parseDatabases(String raw, MongoDbSettings settings) {
        return MongoDbScopes.parse(raw);
    }
}
