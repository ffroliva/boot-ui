package io.github.jdubois.bootui.autoconfigure.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.MongoClientSettings;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.web.PanelsController;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.engine.mongodb.MongoDbInspectionService;
import io.github.jdubois.bootui.engine.mongodb.MongoDbReadBudget;
import io.github.jdubois.bootui.engine.mongodb.MongoDbReadException;
import io.github.jdubois.bootui.engine.mongodb.MongoDbSettings;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import io.github.jdubois.bootui.spi.MongoDbClientAccess;
import io.github.jdubois.bootui.spi.MongoDbCursor;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.bson.BsonDocument;
import org.bson.BsonReader;
import org.bson.BsonWriter;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecRegistries;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

/** Explicit mongodb-live profile; no Docker availability assumptions or skip annotations. */
class MongoDbAdapterLiveTests {
    private static MongoDbLiveFixture fixture;

    @BeforeAll
    static void start() {
        fixture = new MongoDbLiveFixture();
    }

    @AfterAll
    static void stop() {
        if (fixture != null) fixture.close();
    }

    @Test
    void bothExistingClientStylesReadRealCatalogWithoutAnotherPool() {
        try (var sync = fixture.sync("application", 4000);
                var reactive = fixture.reactive("application", 4000)) {
            int pools = fixture.pools.get();
            for (MongoDbClientAccess access :
                    List.of(new SpringSyncMongoDbClientAccess(sync), new SpringReactiveMongoDbClientAccess(reactive))) {
                assertThat(read(access.collectionNames(MongoDbLiveFixture.DATABASE, budget())))
                        .contains("orders", "empty", "order_view", "measurements");
                var collection = access.collection(MongoDbLiveFixture.DATABASE, "orders", budget());
                assertThat(collection.name()).isEqualTo("orders");
                assertThat(collection.type()).isEqualTo("collection");
                assertThat(collection.id()).isNull();
                var indexes = read(access.indexes(MongoDbLiveFixture.DATABASE, "orders", budget()));
                assertThat(indexes)
                        .filteredOn(index -> "tenant_created".equals(index.name()))
                        .singleElement()
                        .satisfies(index -> {
                            assertThat(index.keys())
                                    .extracting(key -> key.field())
                                    .containsExactly("tenant", "created");
                            assertThat(index.keys())
                                    .extracting(key -> key.kind())
                                    .containsExactly("ASC", "DESC");
                            assertThat(index.unique()).isTrue();
                        });
                assertThat(indexes)
                        .filteredOn(index -> "expires_ttl".equals(index.name()))
                        .singleElement()
                        .satisfies(
                                index -> assertThat(index.expireAfterSeconds()).isEqualTo("3600"));
                assertThat(indexes)
                        .filteredOn(index -> "tenant_hashed".equals(index.name()))
                        .singleElement()
                        .satisfies(index -> assertThat(index.keys())
                                .extracting(key -> key.kind())
                                .containsExactly("HASHED"));
                assertThat(indexes)
                        .filteredOn(index -> "attributes_wildcard".equals(index.name()))
                        .singleElement()
                        .satisfies(index -> {
                            assertThat(index.keys())
                                    .extracting(key -> key.field())
                                    .containsExactly("attributes.$**");
                            assertThat(index.keys())
                                    .extracting(key -> key.kind())
                                    .containsExactly("WILDCARD");
                        });
                assertThat(access.collection(MongoDbLiveFixture.DATABASE, "order_view", budget())
                                .type())
                        .isEqualTo("view");
                assertThat(read(access.databaseNames(budget()))).contains(MongoDbLiveFixture.DATABASE);
            }
            assertThat(fixture.pools).hasValue(pools);
            assertThat(sync.getTimeout(TimeUnit.MILLISECONDS)).isEqualTo(4000);
            assertThat(reactive.getTimeout(TimeUnit.MILLISECONDS)).isEqualTo(4000);
            assertThat(fixture.commands).contains("listCollections", "listIndexes", "listDatabases");
        }
    }

    @Test
    void everyFixedOperationBypassesApplicationBsonCodecWithoutChangingEitherClient() {
        PoisonBsonCodec codec = new PoisonBsonCodec();
        var registry = CodecRegistries.fromRegistries(
                CodecRegistries.fromCodecs(codec), MongoClientSettings.getDefaultCodecRegistry());
        try (var sync = fixture.sync("application", 4000, registry);
                var reactive = fixture.reactive("application", 4000, registry)) {
            int pools = fixture.pools.get();
            for (MongoDbClientAccess access :
                    List.of(new SpringSyncMongoDbClientAccess(sync), new SpringReactiveMongoDbClientAccess(reactive))) {
                assertThat(access.serverInformation(MongoDbLiveFixture.DATABASE, budget()))
                        .isNotEmpty();
                assertThat(read(access.databaseNames(budget()))).contains(MongoDbLiveFixture.DATABASE);
                assertThat(read(access.collectionNames(MongoDbLiveFixture.DATABASE, budget())))
                        .contains("orders");
                assertThat(access.collection(MongoDbLiveFixture.DATABASE, "orders", budget())
                                .name())
                        .isEqualTo("orders");
                assertThat(read(access.indexes(MongoDbLiveFixture.DATABASE, "orders", budget())))
                        .isNotEmpty();
                assertThat(codec.invocations).hasValue(0);
            }
            assertThat(fixture.pools).hasValue(pools);
            assertThat(sync.getDatabase(MongoDbLiveFixture.DATABASE)
                            .getCodecRegistry()
                            .get(BsonDocument.class))
                    .isSameAs(codec);
            assertThat(reactive.getDatabase(MongoDbLiveFixture.DATABASE)
                            .getCodecRegistry()
                            .get(BsonDocument.class))
                    .isSameAs(codec);
            assertThatThrownBy(() -> sync.getDatabase(MongoDbLiveFixture.DATABASE)
                            .runCommand(BsonDocument.parse("{\"hello\":1}"), BsonDocument.class))
                    .isInstanceOf(RuntimeException.class);
            assertThat(codec.invocations.get()).isPositive();
            codec.invocations.set(0);
            assertThatThrownBy(() -> {
                        try (var result = new MongoDbReactiveCursor<>(
                                reactive.getDatabase(MongoDbLiveFixture.DATABASE)
                                        .runCommand(BsonDocument.parse("{\"hello\":1}"), BsonDocument.class),
                                Function.identity(),
                                budget())) {
                            result.next();
                        }
                    })
                    .isInstanceOf(MongoDbReadException.class);
            assertThat(codec.invocations.get()).isPositive();
        }
    }

    @Test
    void restrictedRoleCanReadAuthorizedNamesButNotRichMetadataOrIndexes() {
        try (var sync = fixture.sync("restricted", 4000);
                var reactive = fixture.reactive("restricted", 4000)) {
            for (MongoDbClientAccess access :
                    List.of(new SpringSyncMongoDbClientAccess(sync), new SpringReactiveMongoDbClientAccess(reactive))) {
                assertThat(read(access.collectionNames(MongoDbLiveFixture.DATABASE, budget())))
                        .containsExactly("orders");
                assertThat(read(access.databaseNames(budget()))).contains(MongoDbLiveFixture.DATABASE);
                assertThatThrownBy(() -> access.collection(MongoDbLiveFixture.DATABASE, "orders", budget()))
                        .isInstanceOf(MongoDbReadException.class)
                        .hasMessage("DENIED")
                        .hasNoCause();
                assertThatThrownBy(() -> read(access.indexes(MongoDbLiveFixture.DATABASE, "orders", budget())))
                        .isInstanceOf(MongoDbReadException.class)
                        .hasMessage("DENIED")
                        .hasNoCause();
            }
        }
    }

    @Test
    void csotUsesTheApplicationTighterTimeoutOnBothNamesOperations() {
        try (var sync = fixture.sync("application", 150);
                var reactive = fixture.reactive("application", 150)) {
            // Authenticate and initialize owned application resources before timing the fixed failpoint.
            read(new SpringSyncMongoDbClientAccess(sync).collectionNames(MongoDbLiveFixture.DATABASE, budget()));
            read(new SpringReactiveMongoDbClientAccess(reactive)
                    .collectionNames(MongoDbLiveFixture.DATABASE, budget()));
            fixture.blockCatalog(true);
            try {
                for (MongoDbClientAccess access : List.of(
                        new SpringSyncMongoDbClientAccess(sync), new SpringReactiveMongoDbClientAccess(reactive))) {
                    for (boolean databases : List.of(false, true)) {
                        long start = System.nanoTime();
                        assertThatThrownBy(() -> read(
                                        databases
                                                ? access.databaseNames(new MongoDbReadBudget(5000, 4000))
                                                : access.collectionNames(
                                                        MongoDbLiveFixture.DATABASE,
                                                        new MongoDbReadBudget(5000, 4000))))
                                .isInstanceOf(MongoDbReadException.class)
                                .hasMessage("TIMEOUT");
                        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
                                .isLessThan(1500);
                    }
                }
            } finally {
                fixture.blockCatalog(false);
            }
            assertThat(sync.getTimeout(TimeUnit.MILLISECONDS)).isEqualTo(150);
            assertThat(reactive.getTimeout(TimeUnit.MILLISECONDS)).isEqualTo(150);
        }
    }

    @Test
    void passiveReportAndManifestDoNotRunCommandsOrResolveLazyAndProxyClients() {
        try (var sync = fixture.sync("application", 4000);
                var reactive = fixture.reactive("application", 4000);
                var context = new GenericApplicationContext()) {
            AtomicInteger creations = new AtomicInteger();
            AtomicInteger proxyCalls = new AtomicInteger();
            DefaultListableBeanFactory beans = context.getDefaultListableBeanFactory();
            beans.registerSingleton("sync", sync);
            beans.registerSingleton("reactive", reactive);
            beans.registerAlias("sync", "syncAlias");
            RootBeanDefinition lazy = new RootBeanDefinition(com.mongodb.client.MongoClient.class, () -> {
                creations.incrementAndGet();
                throw new AssertionError("Lazy client initialized");
            });
            lazy.setLazyInit(true);
            beans.registerBeanDefinition("lazy", lazy);
            beans.registerSingleton(
                    "proxy",
                    Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[] {com.mongodb.client.MongoClient.class},
                            (object, method, arguments) -> {
                                proxyCalls.incrementAndGet();
                                throw new AssertionError("Proxy invoked");
                            }));
            context.refresh();
            MockEnvironment environment = new MockEnvironment()
                    .withProperty("bootui.mongodb.clients.sync.databases", MongoDbLiveFixture.DATABASE)
                    .withProperty("bootui.mongodb.clients.reactive.databases", MongoDbLiveFixture.DATABASE);
            var provider = new SpringMongoDbProvider(
                    beans,
                    List.of(SpringSyncMongoDbClientAccess.reader(), SpringReactiveMongoDbClientAccess.reader()),
                    environment);
            var service =
                    MongoDbInspectionService.using(provider, exposure(), Clock.systemUTC(), MongoDbSettings.defaults());
            fixture.commands.clear();
            int pools = fixture.pools.get();
            var report = service.report();
            assertThat(report.available()).isTrue();
            assertThat(report.inventory().clients()).hasSize(4);
            assertThat(report.status()).isEqualTo("NOT_READ");
            new PanelsController(context, environment, new BootUiProperties()).panels();
            service.report();
            assertThat(fixture.commands).isEmpty();
            assertThat(fixture.pools).hasValue(pools);
            assertThat(creations).hasValue(0);
            assertThat(proxyCalls).hasValue(0);
        }
    }

    @Test
    void expiredBudgetStopsBeforeNativeCommandsAndEarlyCloseKeepsApplicationClientUsable() {
        try (var sync = fixture.sync("application", 4000);
                var reactive = fixture.reactive("application", 4000)) {
            for (MongoDbClientAccess access :
                    List.of(new SpringSyncMongoDbClientAccess(sync), new SpringReactiveMongoDbClientAccess(reactive))) {
                java.util.concurrent.atomic.AtomicLong ticker = new java.util.concurrent.atomic.AtomicLong();
                MongoDbReadBudget expired = new MongoDbReadBudget(1, 1, ticker::get);
                ticker.set(2_000_000);
                fixture.commands.clear();
                assertThatThrownBy(() -> access.databaseNames(expired)).hasMessage("TIMEOUT");
                assertThat(fixture.commands).isEmpty();
                try (var cursor = access.collectionNames(MongoDbLiveFixture.DATABASE, budget())) {
                    assertThat(cursor.hasNext()).isTrue();
                    cursor.next();
                }
                assertThat(read(access.collectionNames(MongoDbLiveFixture.DATABASE, budget())))
                        .contains("orders");
            }
        }
    }

    private static MongoDbReadBudget budget() {
        return new MongoDbReadBudget(10000, 2000);
    }

    private static <T> List<T> read(MongoDbCursor<T> cursor) {
        try (cursor) {
            List<T> values = new ArrayList<>();
            while (cursor.hasNext()) {
                assertThat(values.size()).isLessThan(100);
                values.add(cursor.next());
            }
            return values;
        }
    }

    private static ExposurePolicy exposure() {
        return new ExposurePolicy() {
            public ValueExposure valueExposure() {
                return ValueExposure.MASKED;
            }

            public boolean maskSecrets() {
                return true;
            }
        };
    }

    private static final class PoisonBsonCodec implements Codec<BsonDocument> {
        final AtomicInteger invocations = new AtomicInteger();

        public BsonDocument decode(BsonReader reader, DecoderContext context) {
            invocations.incrementAndGet();
            throw new IllegalStateException("APPLICATION_CODEC_MUST_NOT_RUN");
        }

        public void encode(BsonWriter writer, BsonDocument value, EncoderContext context) {
            invocations.incrementAndGet();
            throw new IllegalStateException("APPLICATION_CODEC_MUST_NOT_RUN");
        }

        public Class<BsonDocument> getEncoderClass() {
            return BsonDocument.class;
        }
    }
}
