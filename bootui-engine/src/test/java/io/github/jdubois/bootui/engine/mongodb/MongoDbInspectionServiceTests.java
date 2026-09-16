package io.github.jdubois.bootui.engine.mongodb;

import static org.assertj.core.api.Assertions.*;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.*;
import io.github.jdubois.bootui.spi.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MongoDbInspectionServiceTests {
    @Test
    void passiveDiscoveryAndPagingNeverReadMongo() {
        Fixture f = new Fixture();
        MongoDbReport initial = f.service.report();
        assertThat(initial.status()).isEqualTo("NOT_READ");
        assertThat(initial.inventory().clients()).hasSize(1);
        assertThat(f.calls.get()).isZero();
        MongoDbReport result = f.service.inspect(f.request("CONFIGURED"));
        assertThat(result.status()).isEqualTo("READ");
        assertThat(result.inspection().collectionsRetained()).isEqualTo(2);
        int calls = f.calls.get();
        MongoDbReport page = f.service.report(result.inspection().snapshotId(), "INDEXES", null, null, null, 0, 1);
        assertThat(page.catalog().indexes()).hasSize(1);
        assertThat(page.catalog().page().hasMore()).isTrue();
        assertThat(page.catalog().indexes().get(0).keys())
                .extracting(MongoDbIndexKeyDto::kind)
                .containsExactly("DESC", "ASC");
        assertThat(page.catalog().indexes().get(0).expireAfterSeconds()).isEqualTo("9007199254740993");
        assertThat(f.calls.get()).isEqualTo(calls);
        assertThat(f.closed.get()).isEqualTo(3);
    }

    @Test
    void deniedOptionsKeepAuthorizedNamesAndIndependentIndexes() {
        Fixture f = new Fixture();
        f.denied = true;
        MongoDbReport result = f.service.inspect(f.request("CONFIGURED"));
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.inspection().collectionsRetained()).isEqualTo(2);
        assertThat(result.inspection().indexesRetained()).isEqualTo(2);
        assertThat(result.diagnostics()).allMatch(d -> "DENIED".equals(d.code()));
    }

    @Test
    void policyInvalidationAndStalePagesDoNotPerformIo() {
        Fixture f = new Fixture();
        MongoDbReport result = f.service.inspect(f.request("CONFIGURED"));
        int calls = f.calls.get();
        f.mode = ValueExposure.METADATA_ONLY;
        assertThat(f.service.report().status()).isEqualTo("NOT_READ");
        assertThat(f.service.report().inventory().clients().get(0).name()).isEqualTo("******");
        assertThatThrownBy(() -> f.service.report(result.inspection().snapshotId(), "INDEXES", null, null, null, 0, 1))
                .isInstanceOf(MongoDbRequestException.class);
        assertThat(f.calls.get()).isEqualTo(calls);
    }

    @Test
    void configuredScopeDoesNotEnumerateAndEnumerationIsDisabledByDefault() {
        Fixture f = new Fixture();
        assertThatThrownBy(() -> f.service.inspect(f.request("AUTHORIZED_NAMES")))
                .isInstanceOf(MongoDbRequestException.class)
                .hasMessageContaining("disabled");
        assertThat(f.calls.get()).isZero();
        f.service.inspect(f.request("CONFIGURED"));
        assertThat(f.enumerations.get()).isZero();
    }

    @Test
    void strictInputAndUnknownTargetsAreRefusedBeforeIo() {
        Fixture f = new Fixture();
        assertThatThrownBy(() -> MongoDbRequests.inspect(Map.of("clientId", "client", "uri", "mongodb://secret")))
                .isInstanceOf(MongoDbRequestException.class);
        assertThatThrownBy(
                        () -> f.service.inspect(new MongoDbInspectRequest("unknown", "CONFIGURED", null, null, null)))
                .isInstanceOf(MongoDbRequestException.class);
        assertThatThrownBy(() -> f.service.report(null, "INDEXES", null, null, null, -1, 0))
                .isInstanceOf(MongoDbRequestException.class);
        assertThat(f.calls.get()).isZero();
    }

    @Test
    void concurrentActionRejectedAndInFlightExposureResultDiscarded() throws Exception {
        Fixture f = new Fixture();
        f.entered = new CountDownLatch(1);
        f.release = new CountDownLatch(1);
        MongoDbInspectRequest request = f.request("CONFIGURED");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MongoDbReport> running = executor.submit(() -> f.service.inspect(request));
            assertThat(f.entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> f.service.inspect(request))
                    .isInstanceOf(io.github.jdubois.bootui.engine.action.ActionBusyException.class);
            assertThat(f.service.report().status()).isEqualTo("NOT_READ");
            f.mode = ValueExposure.FULL;
            f.service.report();
            f.release.countDown();
            assertThat(running.get(5, TimeUnit.SECONDS).status()).isEqualTo("NOT_READ");
        } finally {
            f.release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void selectedTargetUsesRetainedIdentityNotDisplayName() {
        Fixture f = new Fixture();
        MongoDbReport report = f.service.inspect(f.request("CONFIGURED"));
        String database = report.catalog().databases().get(0).id();
        MongoDbReport collections =
                f.service.report(report.inspection().snapshotId(), "COLLECTIONS", database, null, null, 0, 50);
        MongoDbReport selected = f.service.inspect(new MongoDbInspectRequest(
                report.inspection().clientId(),
                "SELECTED",
                database,
                collections.catalog().collections().get(0).id(),
                report.inspection().snapshotId()));
        assertThat(selected.inspection().collectionsRetained()).isEqualTo(1);
    }

    @Test
    void wholeMetadataByteBoundStopsNestedGrowthAndClosesOwnedCursors() {
        Fixture f = new Fixture();
        f.largeKeys = true;
        var service = f.service(MongoDbSettings.from(key -> key.endsWith("max-metadata-bytes") ? "16384" : null));
        String client = service.report().inventory().clients().get(0).id();
        var result = service.inspect(new MongoDbInspectRequest(client, "CONFIGURED", null, null, null));
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.inspection().truncated()).isTrue();
        assertThat(result.inspection().retainedBytes()).isLessThanOrEqualTo(16384);
        assertThat(result.inspection().indexesRetained()).isZero();
        assertThat(f.closed).hasValue(2);
    }

    @Test
    void wholeItemBoundStopsBeforeMultiplyingAcrossCollections() {
        Fixture f = new Fixture();
        var service = f.service(MongoDbSettings.from(key -> key.endsWith("max-total-items") ? "12" : null));
        String client = service.report().inventory().clients().get(0).id();
        var result = service.inspect(new MongoDbInspectRequest(client, "CONFIGURED", null, null, null));
        assertThat(result.inspection().truncated()).isTrue();
        assertThat(result.inspection().retainedItems()).isLessThanOrEqualTo(12);
        assertThat(result.inspection().collectionsRetained()).isLessThan(2);
        assertThat(f.closed).hasValue(2);
    }

    private static final class Fixture implements MongoDbClientAccess, ExposurePolicy {
        final AtomicInteger calls = new AtomicInteger(),
                closed = new AtomicInteger(),
                enumerations = new AtomicInteger();
        volatile ValueExposure mode = ValueExposure.MASKED;
        boolean denied;
        boolean largeKeys;
        CountDownLatch entered, release;
        final MongoDbInspectionService service = service(MongoDbSettings.defaults());

        MongoDbInspectionService service(MongoDbSettings limits) {
            return MongoDbInspectionService.using(
                    max -> new MongoDbProvider.Discovery(
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
                                    this,
                                    this)),
                            List.of(),
                            false),
                    this,
                    Clock.systemUTC(),
                    limits);
        }

        MongoDbInspectRequest request(String scope) {
            return new MongoDbInspectRequest(
                    service.report().inventory().clients().get(0).id(), scope, null, null, null);
        }

        @Override
        public ValueExposure valueExposure() {
            return mode;
        }

        @Override
        public boolean maskSecrets() {
            return true;
        }

        @Override
        public List<MongoDbSettingDto> serverInformation(String database, MongoDbReadBudget budget) {
            calls.incrementAndGet();
            if (entered != null) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("test release");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new MongoDbReadException("CANCELLED");
                }
            }
            return List.of(new MongoDbSettingDto("version", "8.0.19", "OBSERVED", "buildInfo"));
        }

        @Override
        public MongoDbCursor<String> databaseNames(MongoDbReadBudget budget) {
            enumerations.incrementAndGet();
            return cursor(List.of("app"));
        }

        @Override
        public MongoDbCursor<String> collectionNames(String database, MongoDbReadBudget budget) {
            calls.incrementAndGet();
            return cursor(List.of("products", "orders"));
        }

        @Override
        public MongoDbCollectionDto collection(String database, String name, MongoDbReadBudget budget) {
            calls.incrementAndGet();
            if (denied) throw new MongoDbReadException("DENIED");
            return new MongoDbCollectionDto(
                    null, null, null, name, "collection", false, null, null, false, false, false, List.of(), List.of());
        }

        @Override
        public MongoDbCursor<MongoDbIndexDto> indexes(String database, String name, MongoDbReadBudget budget) {
            calls.incrementAndGet();
            return cursor(List.of(new MongoDbIndexDto(
                    null,
                    null,
                    null,
                    null,
                    "compound",
                    largeKeys
                            ? java.util.stream.IntStream.range(0, 32)
                                    .mapToObj(i -> new MongoDbIndexKeyDto("漢".repeat(255), "ASC"))
                                    .toList()
                            : List.of(new MongoDbIndexKeyDto("category", "DESC"), new MongoDbIndexKeyDto("sku", "ASC")),
                    true,
                    false,
                    false,
                    "9007199254740993",
                    true,
                    false,
                    false,
                    false,
                    List.of())));
        }

        private <T> MongoDbCursor<T> cursor(List<T> rows) {
            Iterator<T> iterator = rows.iterator();
            return new MongoDbCursor<>() {
                public boolean hasNext() {
                    return iterator.hasNext();
                }

                public T next() {
                    return iterator.next();
                }

                public void close() {
                    closed.incrementAndGet();
                }
            };
        }
    }
}
