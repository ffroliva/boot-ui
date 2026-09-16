package io.github.jdubois.bootui.quarkus.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mongodb.MongoException;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.MongoDbIndexDto;
import io.github.jdubois.bootui.core.dto.MongoDbIndexKeyDto;
import io.github.jdubois.bootui.core.dto.MongoDbInspectRequest;
import io.github.jdubois.bootui.engine.mongodb.MongoDbInspectionService;
import io.github.jdubois.bootui.engine.mongodb.MongoDbReadBudget;
import io.github.jdubois.bootui.engine.mongodb.MongoDbSettings;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import io.github.jdubois.bootui.spi.MongoDbClientAccess;
import io.github.jdubois.bootui.spi.MongoDbCursor;
import io.github.jdubois.bootui.spi.MongoDbProvider;
import java.time.Clock;
import java.util.List;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.junit.jupiter.api.Test;

class MongoDbDriverValuesTest {
    @Test
    void retainsOnlyTypedIndexDeclarationsAndExactIntegersInCompoundOrder() {
        var index = MongoDbDriverValues.index(BsonDocument.parse("""
                {"name":"compound","key":{"region":1,"time":-1},"expireAfterSeconds":{"$numberLong":"9007199254740993"},
                 "unique":true,"partialFilterExpression":{"password":"do-not-retain"},
                 "collation":{"locale":"private"},"futureOption":{"secret":"do-not-retain"}}
                """));
        assertThat(index.id()).isNull();
        assertThat(index.keys())
                .containsExactly(new MongoDbIndexKeyDto("region", "ASC"), new MongoDbIndexKeyDto("time", "DESC"));
        assertThat(index.expireAfterSeconds()).isEqualTo("9007199254740993");
        assertThat(index.partialFilterPresent()).isTrue();
        assertThat(index.collationPresent()).isTrue();
        assertThat(index.hasUnsupportedOptions()).isTrue();
        assertThat(index.toString()).doesNotContain("do-not-retain", "private", "password", "futureOption");
    }

    @Test
    void collectionOptionsArePresenceOnlyAndWrongTypesAreNeverStringified() {
        var collection = MongoDbDriverValues.collection(BsonDocument.parse("""
                {"name":"orders","type":"collection","options":{"validator":{"password":"private"},
                 "capped":true,"size":{"$numberLong":"9007199254740993"},"max":{"bad":"private"},
                 "encryptedFields":{"secret":"private"}}}
                """));
        assertThat(collection.cappedSizeBytes()).isEqualTo("9007199254740993");
        assertThat(collection.cappedMaxDocuments()).isNull();
        assertThat(collection.validatorPresent()).isTrue();
        assertThat(collection.encryptedFieldsPresent()).isTrue();
        assertThat(collection.toString()).doesNotContain("private", "password");
    }

    @Test
    void existingTighterTimeoutWinsAndFailuresNeverRetainACause() {
        assertThat(MongoDbDriverValues.timeout(new MongoDbReadBudget(10000, 2000), 25L))
                .isEqualTo(25);
        assertThat(MongoDbDriverValues.timeout(new MongoDbReadBudget(10000, 2000), 0L))
                .isEqualTo(2000);
        var safe = MongoDbDriverValues.failure(new IllegalArgumentException("mongodb://secret:password@private"));
        assertThat(safe.code()).isEqualTo("DISCOVERY_FAILED");
        assertThat(safe.getCause()).isNull();
        assertThat(safe.getMessage()).doesNotContain("password", "private");
    }

    @Test
    void allNativeKeyRepresentationsSurviveRealEngineProjection() {
        var index = MongoDbDriverValues.index(BsonDocument.parse("""
                {"name":"kinds","key":{"ascending":1,"descending":{"$numberLong":"-1"},
                "double":1.0,"decimal":{"$numberDecimal":"-1.0"},"hash":"hashed","words":"text",
                "flat":"2d","sphere":"2dsphere","haystack":"geoHaystack","$**":1,"path.$**":1,
                "invalid":1.5,"unknown":"private"}}
                """));
        List<String> kinds = List.of(
                "ASC",
                "DESC",
                "ASC",
                "DESC",
                "HASHED",
                "TEXT",
                "GEO_2D",
                "GEO_2DSPHERE",
                "GEO_HAYSTACK",
                "WILDCARD",
                "WILDCARD",
                "UNKNOWN",
                "UNKNOWN");
        assertThat(index.keys()).extracting(MongoDbIndexKeyDto::kind).containsExactlyElementsOf(kinds);
        assertThat(project(index).keys()).extracting(MongoDbIndexKeyDto::kind).containsExactlyElementsOf(kinds);
    }

    @Test
    void decoderClippingCarriesSpecificOmissionCode() {
        BsonDocument keys = new BsonDocument();
        for (int i = 0; i < 32; i++) keys.append("field" + i, new BsonInt32(1));
        assertThat(MongoDbDriverValues.index(new BsonDocument("key", keys)).limitations())
                .isEmpty();
        keys.append("omitted", new BsonInt32(1));
        var index = MongoDbDriverValues.index(new BsonDocument("key", keys));
        assertThat(index.keys()).hasSize(32);
        assertThat(index.limitations()).containsExactly("INDEX_KEY_LIMIT");
    }

    @Test
    void nativeServerCodesUseSharedFailureVocabulary() {
        for (int code : List.of(50, 89, 262)) {
            assertThat(MongoDbDriverValues.failure(new MongoException(code, "private"))
                            .code())
                    .isEqualTo("TIMEOUT");
        }
        for (int code : List.of(59, 115, 303)) {
            var error = MongoDbDriverValues.failure(new MongoException(code, "private"));
            assertThat(error.code()).isEqualTo("UNSUPPORTED");
            assertThat(error.getCause()).isNull();
            assertThat(error.getSuppressed()).isEmpty();
        }
    }

    private static MongoDbIndexDto project(MongoDbIndexDto index) {
        MongoDbClientAccess access = mock(MongoDbClientAccess.class);
        when(access.serverInformation(eq("app"), any())).thenReturn(List.of());
        when(access.collectionNames(eq("app"), any())).thenReturn(cursor(List.of("orders")));
        when(access.collection(eq("app"), eq("orders"), any()))
                .thenReturn(MongoDbDriverValues.collection(
                        BsonDocument.parse("{\"name\":\"orders\",\"type\":\"collection\"}")));
        when(access.indexes(eq("app"), eq("orders"), any())).thenReturn(cursor(List.of(index)));
        MongoDbProvider provider = max -> new MongoDbProvider.Discovery(
                List.of(new MongoDbProvider.Client(
                        "client",
                        "application",
                        "SYNC",
                        "test",
                        "INITIALIZED",
                        List.of("app"),
                        List.of(),
                        null,
                        List.of(),
                        access,
                        access)),
                List.of(),
                false);
        ExposurePolicy exposure = new ExposurePolicy() {
            public ValueExposure valueExposure() {
                return ValueExposure.MASKED;
            }

            public boolean maskSecrets() {
                return true;
            }
        };
        var service = MongoDbInspectionService.using(provider, exposure, Clock.systemUTC(), MongoDbSettings.defaults());
        String client = service.report().inventory().clients().get(0).id();
        var report = service.inspect(new MongoDbInspectRequest(client, "CONFIGURED", null, null, null));
        assertThat(report.status()).isEqualTo("READ");
        return service.report(report.inspection().snapshotId(), "INDEXES", null, null, null, 0, 50)
                .catalog()
                .indexes()
                .get(0);
    }

    private static <T> MongoDbCursor<T> cursor(List<T> values) {
        var iterator = values.iterator();
        return new MongoDbCursor<>() {
            public boolean hasNext() {
                return iterator.hasNext();
            }

            public T next() {
                return iterator.next();
            }

            public void close() {}
        };
    }
}
