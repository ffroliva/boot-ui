package io.github.jdubois.bootui.quarkus.mongodb;

import com.mongodb.MongoException;
import com.mongodb.MongoInterruptedException;
import com.mongodb.MongoOperationTimeoutException;
import com.mongodb.MongoSecurityException;
import com.mongodb.MongoTimeoutException;
import io.github.jdubois.bootui.core.dto.MongoDbCollectionDto;
import io.github.jdubois.bootui.core.dto.MongoDbIndexDto;
import io.github.jdubois.bootui.core.dto.MongoDbIndexKeyDto;
import io.github.jdubois.bootui.core.dto.MongoDbSettingDto;
import io.github.jdubois.bootui.engine.mongodb.MongoDbMetadataRules;
import io.github.jdubois.bootui.engine.mongodb.MongoDbReadBudget;
import io.github.jdubois.bootui.engine.mongodb.MongoDbReadException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.bson.BsonDocument;
import org.bson.BsonValue;

final class MongoDbDriverValues {
    private static final Set<String> INDEX_FIELDS = Set.of(
            "v",
            "key",
            "name",
            "ns",
            "unique",
            "sparse",
            "hidden",
            "expireAfterSeconds",
            "partialFilterExpression",
            "collation",
            "wildcardProjection");

    private MongoDbDriverValues() {}

    static long timeout(MongoDbReadBudget budget, Long applicationTimeout) {
        long timeout = budget.operationMillis();
        return applicationTimeout != null && applicationTimeout > 0 ? Math.min(timeout, applicationTimeout) : timeout;
    }

    static MongoDbReadException failure(Throwable error) {
        if (error instanceof MongoDbReadException safe) return safe;
        if (error instanceof MongoInterruptedException || error instanceof InterruptedException) {
            return new MongoDbReadException("CANCELLED");
        }
        if (error instanceof MongoTimeoutException || error instanceof MongoOperationTimeoutException) {
            return new MongoDbReadException("TIMEOUT");
        }
        if (error instanceof MongoSecurityException) return new MongoDbReadException("DENIED");
        if (error instanceof MongoException mongo) {
            return new MongoDbReadException(MongoDbMetadataRules.serverError(mongo.getCode()));
        }
        return new MongoDbReadException("DISCOVERY_FAILED");
    }

    static String string(BsonDocument document, String name) {
        BsonValue value = document.get(name);
        return value != null && value.isString() ? value.asString().getValue() : null;
    }

    static Boolean bool(BsonDocument document, String name) {
        BsonValue value = document.get(name);
        return value != null && value.isBoolean() ? value.asBoolean().getValue() : null;
    }

    static String integer(BsonDocument document, String name) {
        return integer(document.get(name));
    }

    private static String integer(BsonValue value) {
        if (value == null) return null;
        if (value.isInt64()) return Long.toString(value.asInt64().getValue());
        if (value.isInt32()) return Integer.toString(value.asInt32().getValue());
        if (value.isDouble()) {
            double number = value.asDouble().getValue();
            if (Double.isFinite(number) && number == Math.rint(number) && Math.abs(number) <= 9007199254740991d) {
                return Long.toString((long) number);
            }
        }
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
        return null;
    }

    static MongoDbCollectionDto collection(BsonDocument document) {
        BsonDocument options = document.getDocument("options", new BsonDocument());
        return new MongoDbCollectionDto(
                null,
                null,
                null,
                string(document, "name"),
                string(document, "type"),
                bool(options, "capped"),
                integer(options, "size"),
                integer(options, "max"),
                options.containsKey("validator"),
                options.containsKey("collation"),
                options.containsKey("encryptedFields"),
                List.of(),
                List.of());
    }

    static MongoDbIndexDto index(BsonDocument document) {
        List<MongoDbIndexKeyDto> keys = new ArrayList<>();
        boolean omitted = false;
        for (var entry : document.getDocument("key", new BsonDocument()).entrySet()) {
            if (keys.size() == 32) {
                omitted = true;
                break;
            }
            BsonValue value = entry.getValue();
            String token = value.isString() ? value.asString().getValue() : integer(value);
            String kind = MongoDbMetadataRules.indexKind(entry.getKey(), token);
            keys.add(new MongoDbIndexKeyDto(entry.getKey(), kind));
        }
        boolean unsupported = document.keySet().stream().anyMatch(key -> !INDEX_FIELDS.contains(key));
        return new MongoDbIndexDto(
                null,
                null,
                null,
                null,
                string(document, "name"),
                keys,
                bool(document, "unique"),
                bool(document, "sparse"),
                bool(document, "hidden"),
                integer(document, "expireAfterSeconds"),
                document.containsKey("partialFilterExpression"),
                document.containsKey("collation"),
                document.containsKey("wildcardProjection"),
                unsupported,
                omitted ? List.of("INDEX_KEY_LIMIT") : List.of());
    }

    static List<MongoDbSettingDto> server(BsonDocument hello, BsonDocument buildInfo) {
        List<MongoDbSettingDto> values = new ArrayList<>();
        for (String key : List.of("isWritablePrimary", "secondary", "arbiterOnly")) {
            Boolean value = bool(hello, key);
            if (value != null) values.add(new MongoDbSettingDto(key, value.toString(), "OBSERVED", "hello"));
        }
        for (String key : List.of("minWireVersion", "maxWireVersion", "logicalSessionTimeoutMinutes")) {
            String value = integer(hello, key);
            if (value != null) values.add(new MongoDbSettingDto(key, value, "OBSERVED", "hello"));
        }
        String version = string(buildInfo, "version");
        if (version != null) values.add(new MongoDbSettingDto("version", version, "OBSERVED", "buildInfo"));
        return List.copyOf(values);
    }
}
