package io.github.jdubois.bootui.engine.mongodb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.jdubois.bootui.core.SecretValueDetector;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.*;
import io.github.jdubois.bootui.engine.action.ActionBusyException;
import io.github.jdubois.bootui.spi.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class MongoDbReviewRegressionTests {
    @Test
    void recognizableOriginalCredentialsAreMaskedBeforeShorteningInEveryExposureMode() {
        String token = "eyJ" + "A".repeat(40) + "." + "B".repeat(180) + "." + "C".repeat(180);
        assertThat(SecretValueDetector.looksLikeSecret(token)).isTrue();
        for (ValueExposure mode : ValueExposure.values()) {
            Fixture f = new Fixture();
            f.mode = mode;
            f.indexName = token;
            f.field = token;
            var result = f.inspect();
            var page = f.indexPage(result);
            assertThat(page.catalog().indexes()).singleElement().satisfies(index -> {
                assertThat(index.name()).isEqualTo("******");
                assertThat(index.keys().get(0).field()).isEqualTo("******");
            });
            assertThat(page.toString()).doesNotContain(token.substring(0, 32));
            assertThat(page.inspection().truncated()).isFalse();
        }
    }

    @Test
    void configuredOmissionsArePartialButExactlyAtLimitAndSelectedScopesAreNot() {
        Fixture f = new Fixture();
        f.databases = IntStream.range(0, 9).mapToObj(i -> "db" + i).toList();
        var inventory = f.service.report().inventory();
        assertThat(inventory.complete()).isFalse();
        assertThat(inventory.truncated()).isTrue();
        assertThat(inventory.clients().get(0).configuredDatabases()).hasSize(8);
        var report = f.inspect();
        assertThat(report.status()).isEqualTo("PARTIAL");
        assertThat(report.inspection().truncated()).isTrue();
        assertThat(report.inspection().databasesRetained()).isEqualTo(8);
        var client = f.service.report().inventory().clients().get(0);
        var selected = f.service.inspect(new MongoDbInspectRequest(
                client.id(), "SELECTED", client.configuredDatabases().get(0).id(), null, null));
        assertThat(selected.status()).isEqualTo("READ");
        assertThat(selected.inspection().truncated()).isFalse();
        f.databases = f.databases.subList(0, 8);
        assertThat(f.service.report().inventory().complete()).isTrue();
        assertThat(f.inspect().status()).isEqualTo("READ");
    }

    @Test
    void retainedNamesAndServerOnlySuccessRemainPartialAfterTimeout() {
        Fixture f = new Fixture(Map.of("authorized-database-enumeration-enabled", "true"));
        when(f.access.databaseNames(any())).thenAnswer(invocation -> new MongoDbCursor<String>() {
            boolean read;

            public boolean hasNext() {
                if (read) throw new MongoDbReadException("TIMEOUT");
                return true;
            }

            public String next() {
                read = true;
                return "visible";
            }

            public void close() {
                f.closed++;
            }
        });
        var result =
                f.service.inspect(new MongoDbInspectRequest(f.client().id(), "AUTHORIZED_NAMES", null, null, null));
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.catalog().databases())
                .extracting(MongoDbDatabaseDto::name)
                .containsExactly("visible");
        assertThat(result.diagnostics()).extracting(MongoDbDiagnosticDto::code).contains("TIMEOUT");
        assertThat(f.closed).isEqualTo(1);
        when(f.access.collectionNames(anyString(), any())).thenThrow(new MongoDbReadException("TIMEOUT"));
        assertThat(f.inspect().status()).isEqualTo("PARTIAL");
        when(f.access.serverInformation(any(), any())).thenThrow(new MongoDbReadException("DENIED"));
        result = f.inspect();
        assertThat(result.catalog().databases()).hasSize(1);
        assertThat(result.status()).isEqualTo("ERROR");
    }

    @Test
    void acceptedCollectionNamesAreExternalEvidenceWhenAllFurtherReadsFail() {
        Fixture f = new Fixture();
        when(f.access.serverInformation(any(), any())).thenThrow(new MongoDbReadException("DENIED"));
        when(f.access.collection(anyString(), anyString(), any())).thenThrow(new MongoDbReadException("DENIED"));
        when(f.access.indexes(anyString(), anyString(), any())).thenThrow(new MongoDbReadException("TIMEOUT"));
        var result = f.inspect();
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.inspection().collectionsRetained()).isEqualTo(1);
        assertThat(result.inspection().indexesRetained()).isZero();
    }

    @Test
    void growingPassiveInventoryInvalidatesCombinedOverflowWithoutIo() {
        Fixture f = new Fixture(Map.of("max-total-items", "20"));
        var old = f.inspect();
        assertThat(old.inspection().retainedItems()).isLessThanOrEqualTo(20);
        clearInvocations(f.access);
        f.clients = 4;
        var current = f.service.report();
        assertThat(current.inventory().clients()).hasSize(4);
        assertThat(current.inspection()).isNull();
        assertThat(current.status()).isEqualTo("NOT_READ");
        assertThat(current.message()).contains("combined metadata", "invalidated");
        assertStatus(409, () -> f.indexPage(old));
        verifyNoInteractions(f.access);
        var limited = f.inspect();
        assertThat(limited.inspection().truncated()).isTrue();
        assertThat(limited.inspection().retainedItems()).isLessThanOrEqualTo(20);
    }

    @Test
    void fittingInventoryChangesReaccountSnapshotAndTinyInventoryLimitsRemainBounded() {
        Fixture f = new Fixture();
        var old = f.inspect();
        f.clients = 2;
        var current = f.indexPage(old);
        assertThat(current.inspection().snapshotId()).isEqualTo(old.inspection().snapshotId());
        assertThat(current.inspection().retainedItems())
                .isEqualTo(old.inspection().retainedItems() + 3);
        for (int maximum = 1; maximum <= 5; maximum++) {
            Fixture small = new Fixture(Map.of("max-total-items", Integer.toString(maximum)));
            small.clients = 4;
            var report = small.service.report();
            MongoDbRetentionBudget budget = new MongoDbRetentionBudget(small.settings);
            assertThat(budget.retain(report.inventory())).isTrue();
            assertThat(report.inventory().truncated()).isTrue();
        }
    }

    @Test
    void replacementRotatesExecutableIdsBeforeAnySnapshotWithoutChangingOtherClients() {
        Fixture f = new Fixture();
        f.clients = 2;
        var before = f.service.report().inventory().clients();
        f.identities.set(0, new Object());
        assertStatus(
                404,
                () -> f.service.inspect(new MongoDbInspectRequest(
                        before.get(0).id(),
                        "SELECTED",
                        before.get(0).configuredDatabases().get(0).id(),
                        null,
                        null)));
        verifyNoInteractions(f.access);
        var after = f.service.report().inventory().clients();
        assertThat(after.get(0).id()).isNotEqualTo(before.get(0).id());
        assertThat(after.get(0).configuredDatabases().get(0).id())
                .isNotEqualTo(before.get(0).configuredDatabases().get(0).id());
        assertThat(after.get(1).id()).isEqualTo(before.get(1).id());
        assertStatus(
                404,
                () -> f.service.inspect(new MongoDbInspectRequest(
                        after.get(0).id(),
                        "SELECTED",
                        before.get(0).configuredDatabases().get(0).id(),
                        null,
                        null)));
        f.clients = 1;
        f.service.report();
        f.clients = 2;
        assertThat(f.service.report().inventory().clients().get(1).id())
                .isNotEqualTo(before.get(1).id());
    }

    @Test
    void textAndNestedListOmissionsAreTruthfulWithoutAnExactlyAtBoundFalsePositive() {
        Fixture f = new Fixture();
        for (int length : List.of(256, 300)) {
            f.indexName = "x".repeat(length);
            var report = f.indexPage(f.inspect());
            assertThat(report.catalog().indexes().get(0).name()).hasSize(256);
            assertThat(report.inspection().truncated()).isEqualTo(length > 256);
            assertThat(report.status()).isEqualTo(length > 256 ? "PARTIAL" : "READ");
        }
        for (int count : List.of(32, 33)) {
            MongoDbValues values = new MongoDbValues(new MongoDbValues.Policy(ValueExposure.FULL, false), 256);
            var input = new MongoDbTopologyDto(
                    0L,
                    "REPLICA_SET",
                    "MULTIPLE",
                    IntStream.range(0, count)
                            .mapToObj(i -> new MongoDbServerDto("node" + i, "UNKNOWN", "CONNECTED"))
                            .toList(),
                    List.of());
            assertThat(values.topology(input).servers()).hasSize(32);
            assertThat(values.truncated()).isEqualTo(count > 32);
        }
        f.clientName = "n".repeat(300);
        var inventory = f.service.report().inventory();
        assertThat(inventory.truncated()).isTrue();
        assertThat(inventory.complete()).isFalse();
        assertThat(inventory.clients().get(0).limitations()).anyMatch(message -> message.contains("shortened"));
    }

    @Test
    void replacementWhileReadingDiscardsTheOldClientsResult() throws Exception {
        Fixture f = new Fixture();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(f.access.serverInformation(any(), any())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("read release");
            return List.of(new MongoDbSettingDto("version", "8.0.19", "OBSERVED", "buildInfo"));
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var running = executor.submit(f::inspect);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            f.identities.set(0, new Object());
            f.service.report();
            release.countDown();
            var report = running.get(5, TimeUnit.SECONDS);
            assertThat(report.status()).isEqualTo("NOT_READ");
            assertThat(report.inspection()).isNull();
            assertThat(report.message()).contains("changed during inspection", "discarded");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void selectedTargetsDistinguishUnknownMissingSnapshotAndStaleSnapshot() {
        Fixture f = new Fixture();
        assertStatus(
                400,
                () -> f.service.inspect(new MongoDbInspectRequest(f.client().id(), "SELECTED", null, null, null)));
        assertStatus(
                404,
                () -> f.service.inspect(new MongoDbInspectRequest(f.client().id(), "SELECTED", "unknown", null, null)));
        var old = f.inspect();
        var collection = f.service
                .report(old.inspection().snapshotId(), "COLLECTIONS", null, null, null, 0, 50)
                .catalog()
                .collections()
                .get(0);
        assertStatus(
                400,
                () -> f.service.inspect(new MongoDbInspectRequest(
                        f.client().id(), "SELECTED", collection.databaseId(), collection.id(), null)));
        assertStatus(
                404,
                () -> f.service.inspect(new MongoDbInspectRequest(
                        f.client().id(), "SELECTED", collection.databaseId(), "unknown", null)));
        assertStatus(
                409,
                () -> f.service.inspect(
                        new MongoDbInspectRequest(f.client().id(), "SELECTED", "unknown", null, "stale")));
    }

    @Test
    void cleanupFailureIsVisibleWithoutReplacingPrimaryFailure() {
        Fixture f = new Fixture();
        when(f.access.collectionNames(anyString(), any())).thenAnswer(invocation -> new MongoDbCursor<String>() {
            public boolean hasNext() {
                throw new MongoDbReadException("TIMEOUT");
            }

            public String next() {
                throw new AssertionError();
            }

            public void close() {
                throw new MongoDbReadException("CLEANUP_FAILED");
            }
        });
        var report = f.inspect();
        assertThat(report.status()).isEqualTo("PARTIAL");
        assertThat(report.diagnostics())
                .extracting(MongoDbDiagnosticDto::code)
                .containsExactly("TIMEOUT", "CLEANUP_FAILED");
    }

    @Test
    void admissionIsHeldUntilOwnedSynchronousCleanupReturns() throws Exception {
        Fixture f = new Fixture();
        CountDownLatch closing = new CountDownLatch(1), release = new CountDownLatch(1);
        when(f.access.collectionNames(anyString(), any())).thenAnswer(invocation -> new MongoDbCursor<String>() {
            public boolean hasNext() {
                return false;
            }

            public String next() {
                throw new AssertionError();
            }

            public void close() {
                closing.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("cleanup release");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new MongoDbReadException("CANCELLED");
                }
            }
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var running = executor.submit(f::inspect);
            assertThat(closing.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(f::inspect).isInstanceOf(ActionBusyException.class);
            release.countDown();
            assertThat(running.get(5, TimeUnit.SECONDS).status()).isEqualTo("READ");
            assertThat(f.inspect().status()).isEqualTo("READ");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static void assertStatus(int status, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(
                        MongoDbRequestException.class,
                        failure -> assertThat(failure.status()).isEqualTo(status));
    }

    private static final class Fixture implements ExposurePolicy {
        final MongoDbClientAccess access = mock(MongoDbClientAccess.class);
        final List<Object> identities =
                new ArrayList<>(List.of(new Object(), new Object(), new Object(), new Object()));
        final MongoDbSettings settings;
        final MongoDbInspectionService service;
        ValueExposure mode = ValueExposure.FULL;
        List<String> databases = List.of("app");
        String clientName = "application", indexName = "compound", field = "tenant";
        int clients = 1, closed;

        Fixture() {
            this(Map.of());
        }

        Fixture(Map<String, String> properties) {
            settings = MongoDbSettings.from(key -> properties.get(key.substring("bootui.mongodb.".length())));
            when(access.serverInformation(any(), any()))
                    .thenReturn(List.of(new MongoDbSettingDto("version", "8.0.19", "OBSERVED", "buildInfo")));
            when(access.collectionNames(anyString(), any())).thenAnswer(invocation -> cursor(List.of("orders")));
            when(access.collection(anyString(), anyString(), any()))
                    .thenAnswer(invocation -> new MongoDbCollectionDto(
                            null,
                            null,
                            null,
                            invocation.getArgument(1),
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
                    .thenAnswer(invocation -> cursor(List.of(new MongoDbIndexDto(
                            null,
                            null,
                            null,
                            null,
                            indexName,
                            List.of(new MongoDbIndexKeyDto(field, "ASC"), new MongoDbIndexKeyDto("created", "DESC")),
                            false,
                            false,
                            false,
                            null,
                            false,
                            false,
                            false,
                            false,
                            List.of()))));
            service = MongoDbInspectionService.using(
                    max -> new MongoDbProvider.Discovery(
                            IntStream.range(0, clients)
                                    .mapToObj(i -> new MongoDbProvider.Client(
                                            "client-" + i,
                                            clientName,
                                            "SYNC",
                                            null,
                                            "INITIALIZED",
                                            databases,
                                            List.of(),
                                            null,
                                            List.of(),
                                            identities.get(i),
                                            access))
                                    .toList(),
                            List.of(),
                            false),
                    this,
                    Clock.systemUTC(),
                    settings);
        }

        MongoDbClientDto client() {
            return service.report().inventory().clients().get(0);
        }

        MongoDbReport inspect() {
            return service.inspect(new MongoDbInspectRequest(client().id(), "CONFIGURED", null, null, null));
        }

        MongoDbReport indexPage(MongoDbReport report) {
            return service.report(report.inspection().snapshotId(), "INDEXES", null, null, null, 0, 200);
        }

        public ValueExposure valueExposure() {
            return mode;
        }

        public boolean maskSecrets() {
            return false;
        }

        <T> MongoDbCursor<T> cursor(List<T> values) {
            Iterator<T> iterator = values.iterator();
            return new MongoDbCursor<>() {
                public boolean hasNext() {
                    return iterator.hasNext();
                }

                public T next() {
                    return iterator.next();
                }

                public void close() {
                    closed++;
                }
            };
        }
    }
}
