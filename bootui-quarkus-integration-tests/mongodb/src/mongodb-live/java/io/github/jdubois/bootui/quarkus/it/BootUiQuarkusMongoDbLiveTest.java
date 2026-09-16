package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClients;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.engine.mongodb.MongoDbReadBudget;
import io.github.jdubois.bootui.quarkus.mongodb.QuarkusReactiveMongoDbClientAccess;
import io.github.jdubois.bootui.quarkus.mongodb.QuarkusSyncMongoDbClientAccess;
import io.github.jdubois.bootui.spi.MongoDbClientAccess;
import io.github.jdubois.bootui.spi.MongoDbCursor;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.BsonDocument;
import org.bson.BsonReader;
import org.bson.BsonWriter;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecRegistries;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

@QuarkusTest
@TestProfile(MongoDbLiveProfile.class)
@QuarkusTestResource(value = MongoDbLiveResource.class, restrictToAnnotatedClass = true)
class BootUiQuarkusMongoDbLiveTest {
    @TestHTTPResource
    URL baseUrl;

    @Inject
    MongoDbLiveClients clients;

    @Test
    void defaultAndNamedNativeSyncAndReactiveClientsProduceRealPositiveRestEvidence() {
        var probe = new BootUiHttpProbe(baseUrl.toExternalForm());
        for (String name : List.of("default", "named")) {
            for (String style : List.of("SYNC", "REACTIVE")) {
                var initial = probe.get("/bootui/api/mongodb").json();
                String id = MongoDbLiveAssertions.client(initial, name, style)
                        .path("id")
                        .asText();
                io.github.jdubois.bootui.conformance.MongoDbReportContract.verify(
                        probe,
                        baseUrl.toExternalForm().replaceAll("/$", ""),
                        "/bootui/api",
                        id,
                        "fixture_orders",
                        "fixture_compound");
                MongoDbLiveAssertions.verify(probe, "/bootui/api", name, style);
            }
        }
        io.github.jdubois.bootui.conformance.MongoDbProcessContract.browser(
                baseUrl.toExternalForm().replaceAll("/$", ""), "/bootui", true);
    }

    @Test
    void restrictedApplicationRoleKeepsAuthorizedNamesWhenRichMetadataIsDenied() {
        var access = new QuarkusSyncMongoDbClientAccess(clients.restricted());
        try (var names = access.collectionNames(MongoDbLiveResource.DATABASE, new MongoDbReadBudget(3000, 1000))) {
            assertThat(names.hasNext()).isTrue();
            assertThat(names.next()).isEqualTo("fixture_orders");
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> access.collection(
                        MongoDbLiveResource.DATABASE, "fixture_orders", new MongoDbReadBudget(3000, 1000)))
                .hasMessage("DENIED")
                .hasNoCause();
        try (var names = access.databaseNames(new MongoDbReadBudget(3000, 1000))) {
            assertThat(names.hasNext()).isTrue();
            assertThat(names.next()).isEqualTo(MongoDbLiveResource.DATABASE);
        }
    }

    @Test
    void fixedNativeOperationsIgnorePoisonCodecWhileApplicationClientsKeepIt() throws Exception {
        PoisonBsonCodec codec = new PoisonBsonCodec();
        var registry = CodecRegistries.fromRegistries(
                CodecRegistries.fromCodecs(codec), MongoClientSettings.getDefaultCodecRegistry());
        var settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(
                        ConfigProvider.getConfig().getValue("quarkus.mongodb.connection-string", String.class)))
                .codecRegistry(registry)
                .timeout(4000, TimeUnit.MILLISECONDS)
                .build();
        try (var sync = MongoClients.create(settings);
                var reactive = com.mongodb.reactivestreams.client.MongoClients.create(settings)) {
            for (MongoDbClientAccess access : List.of(
                    new QuarkusSyncMongoDbClientAccess(sync), new QuarkusReactiveMongoDbClientAccess(reactive))) {
                assertThat(access.serverInformation(MongoDbLiveResource.DATABASE, budget()))
                        .isNotEmpty();
                assertThat(read(access.databaseNames(budget()))).contains(MongoDbLiveResource.DATABASE);
                assertThat(read(access.collectionNames(MongoDbLiveResource.DATABASE, budget())))
                        .contains("fixture_orders");
                assertThat(access.collection(MongoDbLiveResource.DATABASE, "fixture_orders", budget())
                                .name())
                        .isEqualTo("fixture_orders");
                assertThat(read(access.indexes(MongoDbLiveResource.DATABASE, "fixture_orders", budget())))
                        .isNotEmpty();
                assertThat(codec.invocations).hasValue(0);
            }
            assertThat(sync.getDatabase(MongoDbLiveResource.DATABASE)
                            .getCodecRegistry()
                            .get(BsonDocument.class))
                    .isSameAs(codec);
            assertThat(reactive.getDatabase(MongoDbLiveResource.DATABASE)
                            .getCodecRegistry()
                            .get(BsonDocument.class))
                    .isSameAs(codec);
            assertThatThrownBy(() -> sync.getDatabase(MongoDbLiveResource.DATABASE)
                            .runCommand(BsonDocument.parse("{\"hello\":1}"), BsonDocument.class))
                    .isInstanceOf(RuntimeException.class);
            assertThat(codec.invocations.get()).isPositive();
            codec.invocations.set(0);
            var failed = new CompletableFuture<Boolean>();
            var active = new AtomicReference<Subscription>();
            try {
                reactive.getDatabase(MongoDbLiveResource.DATABASE)
                        .runCommand(BsonDocument.parse("{\"hello\":1}"), BsonDocument.class)
                        .subscribe(new Subscriber<>() {
                            public void onSubscribe(Subscription subscription) {
                                active.set(subscription);
                                subscription.request(1);
                            }

                            public void onNext(BsonDocument value) {
                                failed.complete(false);
                            }

                            public void onError(Throwable error) {
                                failed.complete(true);
                            }

                            public void onComplete() {
                                failed.complete(false);
                            }
                        });
                assertThat(failed.get(5, TimeUnit.SECONDS)).isTrue();
                assertThat(codec.invocations.get()).isPositive();
            } finally {
                Subscription subscription = active.getAndSet(null);
                if (subscription != null) subscription.cancel();
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
