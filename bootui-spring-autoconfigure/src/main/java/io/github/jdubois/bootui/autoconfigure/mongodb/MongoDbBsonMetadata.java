package io.github.jdubois.bootui.autoconfigure.mongodb;

import com.mongodb.MongoException;
import com.mongodb.MongoInterruptedException;
import com.mongodb.MongoSecurityException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.connection.ClusterDescription;
import io.github.jdubois.bootui.core.dto.*;
import io.github.jdubois.bootui.engine.mongodb.MongoDbMetadataRules;
import io.github.jdubois.bootui.engine.mongodb.MongoDbReadException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.bson.BsonDocument;
import org.bson.BsonValue;

/** Typed BSON boundary. Unknown fields and literal-bearing documents never leave the adapter. */
public final class MongoDbBsonMetadata {
    private static final Set<String> INDEX_OPTIONS = Set.of(
            "v",
            "name",
            "ns",
            "key",
            "unique",
            "sparse",
            "hidden",
            "expireAfterSeconds",
            "partialFilterExpression",
            "collation",
            "wildcardProjection");

    private MongoDbBsonMetadata() {}

    public static MongoDbReadException failure(Throwable failure) {
        if (failure instanceof MongoDbReadException safe) return safe;
        if (failure instanceof InterruptedException || failure instanceof MongoInterruptedException) {
            return new MongoDbReadException("CANCELLED");
        }
        if (failure instanceof MongoSecurityException) return new MongoDbReadException("DENIED");
        if (failure instanceof MongoTimeoutException) return new MongoDbReadException("TIMEOUT");
        if (failure instanceof MongoException mongo) {
            return new MongoDbReadException(MongoDbMetadataRules.serverError(mongo.getCode()));
        }
        return new MongoDbReadException("READ_FAILED");
    }

    public static MongoDbTopologyDto topology(ClusterDescription description) {
        if (description == null) return null;
        List<MongoDbServerDto> servers = description.getServerDescriptions().stream()
                .limit(32)
                .map(server -> new MongoDbServerDto(
                        server.getAddress().toString(),
                        server.getType().name(),
                        server.getState().name()))
                .toList();
        return new MongoDbTopologyDto(
                System.currentTimeMillis(),
                description.getType().name(),
                description.getConnectionMode().name(),
                servers,
                description.getServerDescriptions().size() > 32 ? List.of("TOPOLOGY_LIMIT") : List.of());
    }

    public static List<MongoDbSettingDto> server(BsonDocument hello, BsonDocument buildInfo) {
        List<MongoDbSettingDto> result = new ArrayList<>();
        add(result, "version", text(buildInfo.get("version")), "buildInfo");
        add(result, "minWireVersion", integer(hello.get("minWireVersion")), "hello");
        add(result, "maxWireVersion", integer(hello.get("maxWireVersion")), "hello");
        Boolean primary = bool(hello.get("isWritablePrimary"));
        Boolean secondary = bool(hello.get("secondary"));
        if (primary != null) add(result, "writablePrimary", primary.toString(), "hello");
        if (secondary != null) add(result, "secondary", secondary.toString(), "hello");
        return List.copyOf(result);
    }

    private static void add(List<MongoDbSettingDto> result, String key, String value, String source) {
        if (value != null) result.add(new MongoDbSettingDto(key, value, "OBSERVED", source));
    }

    public static MongoDbCollectionDto collection(BsonDocument document) {
        BsonDocument options = object(document.get("options"));
        String type = text(document.get("type"));
        List<String> limitations = new ArrayList<>();
        if (options.containsKey("viewOn") || options.containsKey("pipeline")) {
            limitations.add("View definition is withheld; views are not descended.");
        }
        if (options.containsKey("timeseries")) limitations.add("Time-series options are withheld.");
        return new MongoDbCollectionDto(
                null,
                null,
                null,
                text(document.get("name")),
                type,
                bool(options.get("capped")),
                integer(options.get("size")),
                integer(options.get("max")),
                options.containsKey("validator"),
                options.containsKey("collation"),
                options.containsKey("encryptedFields"),
                List.of(),
                limitations);
    }

    public static MongoDbIndexDto index(BsonDocument document) {
        List<MongoDbIndexKeyDto> keys = new ArrayList<>();
        BsonDocument key = object(document.get("key"));
        int count = 0;
        for (var entry : key.entrySet()) {
            if (count++ == 32) break;
            keys.add(new MongoDbIndexKeyDto(entry.getKey(), keyKind(entry.getKey(), entry.getValue())));
        }
        return new MongoDbIndexDto(
                null,
                null,
                null,
                null,
                text(document.get("name")),
                keys,
                bool(document.get("unique")),
                bool(document.get("sparse")),
                bool(document.get("hidden")),
                integer(document.get("expireAfterSeconds")),
                document.containsKey("partialFilterExpression"),
                document.containsKey("collation"),
                document.containsKey("wildcardProjection"),
                document.keySet().stream().anyMatch(name -> !INDEX_OPTIONS.contains(name)),
                key.size() > 32 ? List.of("INDEX_KEY_LIMIT") : List.of());
    }

    private static String keyKind(String field, BsonValue value) {
        String number = integer(value);
        return MongoDbMetadataRules.indexKind(field, number == null ? text(value) : number);
    }

    public static String text(BsonValue value) {
        return value != null && value.isString() ? value.asString().getValue() : null;
    }

    public static String integer(BsonValue value) {
        if (value == null) return null;
        if (value.isInt64()) return Long.toString(value.asInt64().getValue());
        if (value.isInt32()) return Integer.toString(value.asInt32().getValue());
        if (value.isDecimal128()) {
            try {
                return value.asDecimal128()
                        .getValue()
                        .bigDecimalValue()
                        .toBigIntegerExact()
                        .toString();
            } catch (ArithmeticException invalid) {
                return null;
            }
        }
        if (value.isDouble()) {
            double number = value.asDouble().getValue();
            if (Double.isFinite(number) && number == Math.rint(number) && Math.abs(number) <= 9007199254740991d) {
                return Long.toString((long) number);
            }
        }
        return null;
    }

    private static Boolean bool(BsonValue value) {
        return value != null && value.isBoolean() ? value.asBoolean().getValue() : null;
    }

    private static BsonDocument object(BsonValue value) {
        return value != null && value.isDocument() ? value.asDocument() : new BsonDocument();
    }
}
