package io.github.jdubois.bootui.autoconfigure.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.*;
import io.github.jdubois.bootui.engine.mongodb.*;
import io.github.jdubois.bootui.spi.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MongoDbSerializationTests {
    @Test
    void realSerializedReportsStayWithinTheByteCapDuringAdversarialMetadataAndInventoryGrowth() throws Exception {
        String token = "eyJ" + "A".repeat(40) + "." + "B".repeat(180) + "." + "C".repeat(180);
        String large = "\u0001\\\"/\u6f22".repeat(60);
        for (ValueExposure mode : ValueExposure.values()) {
            var access = mock(MongoDbClientAccess.class);
            when(access.serverInformation(any(), any()))
                    .thenReturn(List.of(new MongoDbSettingDto("version", token, "OBSERVED", large)));
            when(access.collectionNames(anyString(), any())).thenAnswer(invocation -> cursor(List.of("orders")));
            when(access.collection(anyString(), anyString(), any()))
                    .thenReturn(new MongoDbCollectionDto(
                            null,
                            null,
                            null,
                            "orders",
                            "collection",
                            false,
                            null,
                            null,
                            false,
                            false,
                            false,
                            List.of(),
                            List.of()));
            when(access.indexes(anyString(), anyString(), any()))
                    .thenAnswer(invocation -> cursor(List.of(
                            new MongoDbIndexDto(
                                    null,
                                    null,
                                    null,
                                    null,
                                    token,
                                    List.of(new MongoDbIndexKeyDto(token, "ASC")),
                                    false,
                                    false,
                                    false,
                                    null,
                                    false,
                                    false,
                                    false,
                                    false,
                                    List.of()),
                            new MongoDbIndexDto(
                                    null,
                                    null,
                                    null,
                                    null,
                                    large,
                                    java.util.stream.IntStream.range(0, 32)
                                            .mapToObj(i -> new MongoDbIndexKeyDto(large, "ASC"))
                                            .toList(),
                                    false,
                                    false,
                                    false,
                                    null,
                                    false,
                                    false,
                                    false,
                                    false,
                                    List.of()))));
            var count = new java.util.concurrent.atomic.AtomicInteger(1);
            var identities = java.util.stream.IntStream.range(0, 16)
                    .mapToObj(i -> new Object())
                    .toList();
            var settings = MongoDbSettings.from(key -> key.endsWith("max-metadata-bytes") ? "16384" : null);
            ExposurePolicy policy = new ExposurePolicy() {
                public ValueExposure valueExposure() {
                    return mode;
                }

                public boolean maskSecrets() {
                    return false;
                }
            };
            var service = MongoDbInspectionService.using(
                    max -> new MongoDbProvider.Discovery(
                            java.util.stream.IntStream.range(0, count.get())
                                    .mapToObj(i -> new MongoDbProvider.Client(
                                            "client-" + i,
                                            token,
                                            "SYNC",
                                            null,
                                            "INITIALIZED",
                                            List.of("app"),
                                            List.of(),
                                            new MongoDbTopologyDto(
                                                    0L,
                                                    "STANDALONE",
                                                    "SINGLE",
                                                    List.of(new MongoDbServerDto(large, "STANDALONE", "CONNECTED")),
                                                    List.of()),
                                            List.of(),
                                            identities.get(i),
                                            access))
                                    .toList(),
                            List.of(),
                            false),
                    policy,
                    java.time.Clock.systemUTC(),
                    settings);
            var initial = service.report();
            assertBoundedJson(initial, token, settings);
            var inspected = service.inspect(new MongoDbInspectRequest(
                    initial.inventory().clients().get(0).id(), "CONFIGURED", null, null, null));
            assertBoundedJson(inspected, token, settings);
            var indexes = service.report(inspected.inspection().snapshotId(), "INDEXES", null, null, null, 0, 200);
            assertThat(indexes.catalog().indexes()).isNotEmpty();
            assertThat(indexes.catalog().indexes().get(0).name()).isEqualTo("******");
            assertBoundedJson(indexes, token, settings);
            clearInvocations(access);
            for (int clients : List.of(2, 8, 16, 1)) {
                count.set(clients);
                assertBoundedJson(service.report(), token, settings);
            }
            verifyNoInteractions(access);
        }
    }

    private static void assertBoundedJson(MongoDbReport report, String token, MongoDbSettings settings)
            throws Exception {
        byte[] jackson3 = new JsonMapper().writeValueAsBytes(report);
        byte[] jackson2 = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(report);
        assertThat(jackson3).isEqualTo(jackson2).hasSizeLessThanOrEqualTo(settings.maxMetadataBytes());
        assertThat(new String(jackson3, java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain(token.substring(0, 32));
        if (report.inspection() != null) {
            assertThat(report.inspection().retainedItems()).isLessThanOrEqualTo(settings.maxTotalItems());
            assertThat(report.inspection().retainedBytes())
                    .isBetween((long) jackson3.length, (long) settings.maxMetadataBytes());
        }
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

    @Test
    void completeReportAndNestedRecordsAreByteIdenticalUnderBothJacksonGenerations() throws Exception {
        var capability = new MongoDbCapabilityDto("indexes", "AVAILABLE", null, null, "listIndexes");
        var setting = new MongoDbSettingDto("version", "8.0.19", "OBSERVED", "buildInfo");
        var database = new MongoDbDatabaseDto("db", "client", "orders", "CONFIGURED", List.of(capability));
        var topology = new MongoDbTopologyDto(
                1L,
                "STANDALONE",
                "SINGLE",
                List.of(new MongoDbServerDto("localhost:27017", "STANDALONE", "CONNECTED")),
                List.of());
        var client = new MongoDbClientDto(
                "client",
                "application",
                "REACTIVE",
                null,
                "INITIALIZED",
                true,
                List.of(database),
                List.of(setting),
                topology,
                List.of());
        var collection = new MongoDbCollectionDto(
                "collection",
                "db",
                "client",
                "orders",
                "collection",
                null,
                "9007199254740993",
                null,
                true,
                false,
                false,
                List.of(capability),
                List.of());
        var index = new MongoDbIndexDto(
                "index",
                "collection",
                "db",
                "client",
                "compound",
                List.of(new MongoDbIndexKeyDto("tenant", "ASC"), new MongoDbIndexKeyDto("created", "DESC")),
                true,
                null,
                false,
                "9223372036854775807",
                true,
                false,
                true,
                false,
                List.of());
        var inspection = new MongoDbInspectionDto(
                "snapshot",
                "client",
                "CONFIGURED",
                "PARTIAL",
                1L,
                2L,
                null,
                null,
                List.of(setting),
                List.of(capability),
                1,
                1,
                1,
                10,
                4096,
                false,
                List.of("Restricted scope"));
        var limits = io.github.jdubois.bootui.engine.mongodb.MongoDbSettings.defaults()
                .dto();
        var inventory = new MongoDbInventoryDto(1L, List.of(client), true, false, List.of());
        for (String section : List.of("DATABASES", "COLLECTIONS", "INDEXES")) {
            var page = new MongoDbCatalogPageDto(
                    section,
                    new PageMetadata(1, 1, 0, 50, 1, false),
                    section.equals("DATABASES") ? List.of(database) : List.of(),
                    section.equals("COLLECTIONS") ? List.of(collection) : List.of(),
                    section.equals("INDEXES") ? List.of(index) : List.of());
            var report = new MongoDbReport(
                    true,
                    true,
                    null,
                    "PARTIAL",
                    null,
                    "Metadata only",
                    "MASKED",
                    inventory,
                    inspection,
                    page,
                    limits,
                    List.of(new MongoDbDiagnosticDto("collection", "options", "DENIED", "Metadata unavailable")));
            assertThat(new JsonMapper().writeValueAsBytes(report))
                    .isEqualTo(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(report));
        }
    }

    @Test
    void jacksonThreePreservesNullableOptionsExactIntegersAndIndexOrder() throws Exception {
        var index = new MongoDbIndexDto(
                "index",
                "collection",
                "database",
                "client",
                "ordered",
                List.of(new MongoDbIndexKeyDto("tenant", "ASC"), new MongoDbIndexKeyDto("created", "DESC")),
                null,
                false,
                null,
                "9007199254740993",
                true,
                false,
                false,
                false,
                List.of());
        var json = new JsonMapper().readTree(new JsonMapper().writeValueAsString(index));
        assertThat(json.get("unique").isNull()).isTrue();
        assertThat(json.get("expireAfterSeconds").asString()).isEqualTo("9007199254740993");
        assertThat(json.get("keys").get(0).get("field").asString()).isEqualTo("tenant");
        assertThat(json.get("keys").get(1).get("kind").asString()).isEqualTo("DESC");
        assertThat(json.get("limitations").isArray()).isTrue();
    }

    @Test
    void appendedRepositoryMetadataIsImmutableAndLegacyConstructorsStillWork() {
        var fields = new ArrayList<RepositoryMappedFieldDto>();
        fields.add(new RepositoryMappedFieldDto("id", "_id", "String", "NONE", "STATIC"));
        var mapping = new RepositoryDocumentMappingDto("orders", "STATIC", "id", null, fields, true);
        fields.clear();
        assertThat(mapping.fields()).hasSize(1);
        var warnings = new ArrayList<>(List.of("incomplete"));
        var discovery = new RepositoryDiscoveryDto(false, false, warnings);
        warnings.clear();
        assertThat(discovery.warnings()).containsExactly("incomplete");
        assertThat(new RepositoryDto("bean", "Interface", "Domain", "Id", "JPA", null, 1, 0).mongodb())
                .isNull();
        assertThat(new RepositoryMethodDto("find", "find()", "QUERY", "select 1", false, null).query())
                .isEqualTo("select 1");
    }
}
