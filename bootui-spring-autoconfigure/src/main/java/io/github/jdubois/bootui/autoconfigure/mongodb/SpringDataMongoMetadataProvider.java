package io.github.jdubois.bootui.autoconfigure.mongodb;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.*;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Static annotation extraction; never uses a converter, IndexResolver, entity collection accessor or SpEL. */
public final class SpringDataMongoMetadataProvider {
    private static final String MONGO = "org.springframework.data.mongodb.";
    private static final Pattern COMPOUND_KEY =
            Pattern.compile("\\s*(['\"])([\\w.$-]{1,256})\\1\\s*:\\s*(-1|1|['\"](?:hashed|text|2d|2dsphere)['\"])\\s*");
    private static final SecretMasker MASKER = new SecretMasker();
    private final ExposurePolicy exposure;

    public SpringDataMongoMetadataProvider(ExposurePolicy exposure) {
        this.exposure = exposure;
    }

    public RepositoryMongoDbDto describe(Class<?> domain) {
        return describe(domain, true);
    }

    public RepositoryMongoDbDto summary(Class<?> domain) {
        return describe(domain, false);
    }

    private RepositoryMongoDbDto describe(Class<?> domain, boolean detail) {
        if (domain == null) return null;
        List<String> limitations = new ArrayList<>();
        limitations.add("Declared metadata only; no observed-index comparison.");
        limitations.add("Client/database binding is unverified; opening MongoDB does not inspect.");
        Annotation document = annotation(domain, MONGO + "core.mapping.Document");
        String collection = first(text(document, "collection"), text(document, "value"));
        String collectionState = dynamic(collection) ? "DYNAMIC" : "STATIC";
        if (collection == null || collection.isBlank()) {
            String simple = domain.getSimpleName();
            collection = simple.isEmpty() ? null : Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
            collectionState = "CONVENTION";
        }
        if ("DYNAMIC".equals(collectionState)) {
            collection = null;
            limitations.add("Dynamic collection name was not evaluated.");
        }
        if (!detail) {
            return new RepositoryMongoDbDto(
                    "UNRESOLVED",
                    null,
                    null,
                    null,
                    new RepositoryDocumentMappingDto(safe(collection), collectionState, null, null, List.of(), false),
                    List.of(),
                    limitations);
        }
        List<RepositoryMappedFieldDto> fields = new ArrayList<>();
        List<RepositoryDeclaredIndexDto> indexes = new ArrayList<>();
        String id = null;
        String version = null;
        boolean complete = true;
        int depth = 0;
        try {
            for (Class<?> current = domain;
                    current != null && current != Object.class;
                    current = current.getSuperclass()) {
                if (++depth > 16) {
                    complete = false;
                    break;
                }
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())
                            || Modifier.isTransient(field.getModifiers())
                            || field.isSynthetic()
                            || annotation(field, "org.springframework.data.annotation.Transient") != null) continue;
                    if (fields.size() >= 128) {
                        complete = false;
                        break;
                    }
                    Annotation mapped = annotation(field, MONGO + "core.mapping.Field");
                    String persisted = first(text(mapped, "name"), text(mapped, "value"));
                    if (persisted == null) persisted = field.getName();
                    boolean isId = annotation(field, "org.springframework.data.annotation.Id") != null
                            || annotation(field, MONGO + "core.mapping.MongoId") != null
                            || (mapped == null && field.getName().equals("id"));
                    if (isId) {
                        persisted = "_id";
                        id = safe(field.getName());
                    }
                    if (annotation(field, "org.springframework.data.annotation.Version") != null)
                        version = safe(field.getName());
                    String state = dynamic(persisted) ? "DYNAMIC" : "STATIC";
                    String reference = annotation(field, MONGO + "core.mapping.DBRef") != null
                            ? "DBREF"
                            : annotation(field, MONGO + "core.mapping.DocumentReference") != null
                                    ? "DOCUMENT_REFERENCE"
                                    : "NONE";
                    fields.add(new RepositoryMappedFieldDto(
                            safe(field.getName()),
                            "DYNAMIC".equals(state) ? null : safe(persisted),
                            safe(field.getType().getTypeName()),
                            reference,
                            state));
                    for (Annotation declared : field.getAnnotations()) {
                        if (indexes.size() >= 64) {
                            complete = false;
                            break;
                        }
                        RepositoryDeclaredIndexDto index = fieldIndex(declared, persisted);
                        if (index != null) indexes.add(index);
                    }
                }
                for (Annotation declared : current.getAnnotations()) {
                    if (indexes.size() >= 64) {
                        complete = false;
                        break;
                    }
                    String type = declared.annotationType().getName();
                    if (type.equals(MONGO + "core.index.CompoundIndex")) {
                        indexes.add(compoundIndex(declared));
                    } else if (type.equals(MONGO + "core.index.CompoundIndexes")) {
                        Object value = attribute(declared, "value");
                        if (value instanceof Annotation[] array) {
                            for (Annotation index : array) {
                                if (indexes.size() >= 64) {
                                    complete = false;
                                    break;
                                }
                                indexes.add(compoundIndex(index));
                            }
                        }
                    }
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            complete = false;
            limitations.add("Some static mapping metadata could not be read.");
        }
        if (!complete) limitations.add("Static mapping coverage is incomplete or limited.");
        limitations.add("Property-access mappings, custom converters and nested mapping resolution are not evaluated.");
        return new RepositoryMongoDbDto(
                "UNRESOLVED",
                null,
                null,
                null,
                new RepositoryDocumentMappingDto(safe(collection), collectionState, id, version, fields, complete),
                indexes,
                limitations);
    }

    public RepositoryQueryMetadataDto query(Method method, String origin) {
        Annotation query = annotation(method, MONGO + "repository.Query");
        Annotation aggregation = annotation(method, MONGO + "repository.Aggregation");
        Annotation declaration = aggregation == null ? query : aggregation;
        String kind = aggregation != null
                ? "MONGO_AGGREGATION"
                : query != null
                        ? "MONGO_QUERY"
                        : "CRUD".equals(origin) ? "CRUD" : "FRAGMENT".equals(origin) ? "CUSTOM" : "DERIVED";
        boolean dynamic = false;
        Integer stages = null;
        if (aggregation != null) {
            Object pipeline = attribute(aggregation, "pipeline");
            if (pipeline instanceof String[] values) {
                stages = Math.min(values.length, 128);
                for (int i = 0; i < Math.min(values.length, 128); i++) dynamic |= dynamic(values[i]);
            }
        }
        for (String name : List.of("value", "fields", "sort", "collation", "name")) {
            dynamic |= dynamic(text(declaration, name));
        }
        return new RepositoryQueryMetadataDto(
                "MONGODB",
                kind,
                declaration != null,
                dynamic,
                stages,
                present(query, "fields"),
                present(query, "sort"),
                present(declaration, "collation"),
                declaration != null);
    }

    private RepositoryDeclaredIndexDto fieldIndex(Annotation index, String persisted) {
        String name = index.annotationType().getName();
        String kind;
        if (name.equals(MONGO + "core.index.Indexed")) {
            Object direction = attribute(index, "direction");
            kind = direction instanceof Enum<?> value && value.name().equals("DESCENDING") ? "DESC" : "ASC";
        } else if (name.equals(MONGO + "core.index.HashIndexed")) kind = "HASHED";
        else if (name.equals(MONGO + "core.index.TextIndexed")) kind = "TEXT";
        else if (name.equals(MONGO + "core.index.GeoSpatialIndexed")) {
            Object type = attribute(index, "type");
            kind = type instanceof Enum<?> value && value.name().equals("GEO_2DSPHERE") ? "GEO_2DSPHERE" : "GEO_2D";
        } else if (name.equals(MONGO + "core.index.WildcardIndexed")) kind = "WILDCARD";
        else return null;
        String indexName = text(index, "name");
        boolean dynamic = dynamic(indexName) || dynamic(persisted) || dynamic(text(index, "expireAfter"));
        return new RepositoryDeclaredIndexDto(
                dynamic ? null : safe(indexName),
                dynamic ? "DYNAMIC" : "DECLARED",
                dynamic(persisted) ? List.of() : List.of(new RepositoryIndexKeyDto(safe(persisted), kind)),
                bool(index, "unique"),
                bool(index, "sparse"),
                ttl(index),
                present(index, "partialFilter"),
                present(index, "collation"),
                present(index, "wildcardProjection"),
                !name.equals(MONGO + "core.index.Indexed"));
    }

    private RepositoryDeclaredIndexDto compoundIndex(Annotation index) {
        String definition = text(index, "def");
        String name = text(index, "name");
        boolean dynamic = dynamic(definition) || dynamic(name);
        List<RepositoryIndexKeyDto> keys = new ArrayList<>();
        boolean unsupported = false;
        if (!dynamic
                && definition != null
                && definition.length() <= 8192
                && definition.strip().startsWith("{")
                && definition.strip().endsWith("}")) {
            String body = definition.strip();
            body = body.substring(1, body.length() - 1);
            String[] parts = body.split(",", -1);
            if (parts.length > 32) unsupported = true;
            else
                for (String part : parts) {
                    Matcher match = COMPOUND_KEY.matcher(part);
                    if (!match.matches()) {
                        unsupported = true;
                        break;
                    }
                    String direction = match.group(3).replace("'", "").replace("\"", "");
                    String kind =
                            switch (direction) {
                                case "1" -> "ASC";
                                case "-1" -> "DESC";
                                case "hashed" -> "HASHED";
                                case "text" -> "TEXT";
                                case "2d" -> "GEO_2D";
                                case "2dsphere" -> "GEO_2DSPHERE";
                                default -> "UNKNOWN";
                            };
                    keys.add(new RepositoryIndexKeyDto(safe(match.group(2)), kind));
                }
        } else if (!dynamic) unsupported = true;
        if (unsupported) keys.clear();
        return new RepositoryDeclaredIndexDto(
                dynamic ? null : safe(name),
                dynamic ? "DYNAMIC" : unsupported ? "UNRESOLVED" : "DECLARED",
                keys,
                bool(index, "unique"),
                bool(index, "sparse"),
                null,
                present(index, "partialFilter"),
                present(index, "collation"),
                false,
                unsupported);
    }

    private static String ttl(Annotation index) {
        String ttl = text(index, "expireAfter");
        if (ttl == null || ttl.isBlank() || dynamic(ttl) || ttl.length() > 64) return null;
        try {
            if (ttl.matches("[0-9]{1,19}")) return Long.toString(Long.parseLong(ttl));
            if (ttl.matches("[0-9]{1,18}[smhd]")) {
                long multiplier =
                        switch (ttl.charAt(ttl.length() - 1)) {
                            case 'm' -> 60;
                            case 'h' -> 3600;
                            case 'd' -> 86400;
                            default -> 1;
                        };
                return Long.toString(
                        Math.multiplyExact(Long.parseLong(ttl.substring(0, ttl.length() - 1)), multiplier));
            }
            Duration duration = Duration.parse(ttl);
            return duration.isNegative() || duration.getNano() != 0 ? null : Long.toString(duration.getSeconds());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String safe(String text) {
        if (text == null || text.isBlank()) return null;
        if (exposure.valueExposure() == ValueExposure.METADATA_ONLY) return SecretMasker.MASKED_VALUE;
        if (text.length() > 256
                || text.chars().anyMatch(Character::isISOControl)
                || text.contains("://")
                || dynamic(text)) return SecretMasker.MASKED_VALUE;
        return (String) MASKER.mask("mongodb.metadata", text);
    }

    private static Annotation annotation(AnnotatedElement element, String type) {
        for (Annotation value : element.getAnnotations()) {
            if (value.annotationType().getName().equals(type)) return value;
        }
        return null;
    }

    private static Object attribute(Annotation annotation, String name) {
        if (annotation == null) return null;
        try {
            return annotation.annotationType().getMethod(name).invoke(annotation);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    private static String text(Annotation annotation, String name) {
        Object value = attribute(annotation, name);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static Boolean bool(Annotation annotation, String name) {
        Object value = attribute(annotation, name);
        return value instanceof Boolean flag ? flag : null;
    }

    private static boolean present(Annotation annotation, String name) {
        return text(annotation, name) != null;
    }

    private static boolean dynamic(String value) {
        return value != null && (value.contains("#{") || value.contains("${") || value.contains("?#{"));
    }

    private static String first(String first, String second) {
        return first == null ? second : first;
    }
}
