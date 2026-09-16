package io.github.jdubois.bootui.autoconfigure.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoException;
import com.mongodb.ServerAddress;
import com.mongodb.connection.ClusterConnectionMode;
import com.mongodb.connection.ClusterDescription;
import com.mongodb.connection.ClusterType;
import com.mongodb.connection.ServerConnectionState;
import com.mongodb.connection.ServerDescription;
import com.mongodb.connection.ServerType;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.MongoDbIndexDto;
import io.github.jdubois.bootui.core.dto.MongoDbIndexKeyDto;
import io.github.jdubois.bootui.core.dto.MongoDbInspectRequest;
import io.github.jdubois.bootui.engine.mongodb.MongoDbInspectionService;
import io.github.jdubois.bootui.engine.mongodb.MongoDbSettings;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import io.github.jdubois.bootui.spi.MongoDbClientAccess;
import io.github.jdubois.bootui.spi.MongoDbCursor;
import io.github.jdubois.bootui.spi.MongoDbProvider;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MongoDbBsonMetadataTests {
    @Test
    void decodesOnlyStructuralIndexFieldsAndKeepsExactNumbersAndOrder() throws Exception {
        var index = MongoDbBsonMetadata.index(BsonDocument.parse("""
                {"name":"ordered","key":{"tenant":1,"created":-1,"token":"hashed"},
                 "expireAfterSeconds":{"$numberLong":"9007199254740993"},"unique":true,
                 "partialFilterExpression":{"password":"SECRET_SENTINEL"},
                 "collation":{"locale":"SECRET_SENTINEL"},"wildcardProjection":{"SECRET_SENTINEL":1},
                 "custom":{"secret":"SECRET_SENTINEL"}}
                """));
        assertThat(index.keys()).extracting(key -> key.field()).containsExactly("tenant", "created", "token");
        assertThat(index.keys()).extracting(key -> key.kind()).containsExactly("ASC", "DESC", "HASHED");
        assertThat(index.expireAfterSeconds()).isEqualTo("9007199254740993");
        assertThat(index.partialFilterPresent()).isTrue();
        assertThat(index.collationPresent()).isTrue();
        assertThat(index.wildcardProjectionPresent()).isTrue();
        assertThat(index.hasUnsupportedOptions()).isTrue();
        assertThat(new JsonMapper().writeValueAsString(index)).doesNotContain("SECRET_SENTINEL");
    }

    @Test
    void collectionOptionsExposePresenceNotLiterals() throws Exception {
        var collection = MongoDbBsonMetadata.collection(BsonDocument.parse("""
                {"name":"events","type":"collection","options":{"capped":true,
                 "size":{"$numberLong":"9007199254740993"},"max":25,
                 "validator":{"SECRET_SENTINEL":true},"encryptedFields":{"SECRET_SENTINEL":true},
                 "pipeline":[{"SECRET_SENTINEL":true}],"collation":{"locale":"SECRET_SENTINEL"}}}
                """));
        assertThat(collection.cappedSizeBytes()).isEqualTo("9007199254740993");
        assertThat(collection.validatorPresent()).isTrue();
        assertThat(collection.encryptedFieldsPresent()).isTrue();
        assertThat(new JsonMapper().writeValueAsString(collection)).doesNotContain("SECRET_SENTINEL");
    }

    @Test
    void nativeErrorsRetainNeitherThrowableNorServerMessage() {
        for (int code : List.of(13, 18, 26, 50, 59, 89, 115, 262, 303, 999)) {
            var error = MongoDbBsonMetadata.failure(new MongoCommandException(
                    BsonDocument.parse("{\"ok\":0,\"code\":" + code + ",\"errmsg\":\"SECRET_SENTINEL\"}"),
                    new ServerAddress("localhost", 27017)));
            assertThat(error.getCause()).isNull();
            assertThat(error.getMessage()).doesNotContain("SECRET_SENTINEL");
        }
    }

    @Test
    void allSupportedNumericKeyRepresentationsUseNeutralKinds() {
        var index = MongoDbBsonMetadata.index(BsonDocument.parse("""
                {"name":"kinds","key":{"ascending":1,"descending":{"$numberLong":"-1"},"double":1.0,
                "decimal":{"$numberDecimal":"-1.0"},"hash":"hashed","words":"text","flat":"2d",
                "sphere":"2dsphere","haystack":"geoHaystack","$**":1,"path.$**":1,"invalid":1.5}}
                """));
        assertThat(index.keys())
                .extracting(key -> key.kind())
                .containsExactly(
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
                        "UNKNOWN");
        assertThat(project(index).keys())
                .extracting(MongoDbIndexKeyDto::kind)
                .containsExactlyElementsOf(
                        index.keys().stream().map(MongoDbIndexKeyDto::kind).toList());
    }

    @Test
    void decoderClippingCarriesSpecificOmissionCode() {
        BsonDocument keys = new BsonDocument();
        for (int i = 0; i < 32; i++) keys.append("field" + i, new BsonInt32(1));
        assertThat(MongoDbBsonMetadata.index(new BsonDocument("key", keys)).limitations())
                .isEmpty();
        keys.append("omitted", new BsonInt32(1));
        var index = MongoDbBsonMetadata.index(new BsonDocument("key", keys));
        assertThat(index.keys()).hasSize(32);
        assertThat(index.limitations()).containsExactly("INDEX_KEY_LIMIT");
    }

    @Test
    void nativeServerCodesUseSharedFailureVocabulary() {
        for (int code : List.of(50, 89, 262)) {
            assertThat(MongoDbBsonMetadata.failure(new MongoException(code, "private"))
                            .code())
                    .isEqualTo("TIMEOUT");
        }
        for (int code : List.of(59, 115, 303)) {
            assertThat(MongoDbBsonMetadata.failure(new MongoException(code, "private"))
                            .code())
                    .isEqualTo("UNSUPPORTED");
        }
    }

    @Test
    void topologyClippingCarriesSpecificOmissionCode() {
        ClusterDescription description = mock(ClusterDescription.class);
        ServerDescription server = mock(ServerDescription.class);
        when(server.getAddress()).thenReturn(new ServerAddress("localhost", 27017));
        when(server.getType()).thenReturn(ServerType.UNKNOWN);
        when(server.getState()).thenReturn(ServerConnectionState.CONNECTING);
        when(description.getType()).thenReturn(ClusterType.UNKNOWN);
        when(description.getConnectionMode()).thenReturn(ClusterConnectionMode.MULTIPLE);
        when(description.getServerDescriptions()).thenReturn(Collections.nCopies(32, server));
        assertThat(MongoDbBsonMetadata.topology(description).limitations()).isEmpty();
        when(description.getServerDescriptions()).thenReturn(Collections.nCopies(33, server));
        var topology = MongoDbBsonMetadata.topology(description);
        assertThat(topology.servers()).hasSize(32);
        assertThat(topology.limitations()).containsExactly("TOPOLOGY_LIMIT");
    }

    private static MongoDbIndexDto project(MongoDbIndexDto index) {
        MongoDbClientAccess access = mock(MongoDbClientAccess.class);
        when(access.serverInformation(eq("app"), any())).thenReturn(List.of());
        when(access.collectionNames(eq("app"), any())).thenReturn(cursor(List.of("orders")));
        when(access.collection(eq("app"), eq("orders"), any()))
                .thenReturn(MongoDbBsonMetadata.collection(
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
