package io.github.jdubois.bootui.quarkus.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.mongodb.MongoClientSettings;
import com.mongodb.reactivestreams.client.ListDatabasesPublisher;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCluster;
import io.github.jdubois.bootui.engine.mongodb.MongoDbReadBudget;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

class QuarkusReactiveMongoDbClientAccessTest {
    @Test
    @SuppressWarnings("unchecked")
    void authorizedEnumerationSubscribesOnlyToTheExistingClientsTimeoutView() {
        MongoClient client = mock(MongoClient.class);
        MongoCluster view = mock(MongoCluster.class);
        ListDatabasesPublisher<BsonDocument> listing = mock(ListDatabasesPublisher.class, RETURNS_SELF);
        AtomicBoolean cancelled = new AtomicBoolean();
        when(client.getTimeout(TimeUnit.MILLISECONDS)).thenReturn(40L);
        when(client.withTimeout(40, TimeUnit.MILLISECONDS)).thenReturn(view);
        when(view.withCodecRegistry(MongoClientSettings.getDefaultCodecRegistry()))
                .thenReturn(view);
        when(view.listDatabases(BsonDocument.class)).thenReturn(listing);
        doAnswer(invocation -> {
                    Subscriber<? super BsonDocument> target = invocation.getArgument(0);
                    target.onSubscribe(new Subscription() {
                        @Override
                        public void request(long count) {
                            assertThat(count).isEqualTo(1);
                            target.onNext(BsonDocument.parse("{\"name\":\"orders\"}"));
                            target.onComplete();
                        }

                        @Override
                        public void cancel() {
                            cancelled.set(true);
                        }
                    });
                    return null;
                })
                .when(listing)
                .subscribe(any());
        try (var names =
                new QuarkusReactiveMongoDbClientAccess(client).databaseNames(new MongoDbReadBudget(1000, 500))) {
            assertThat(names.next()).isEqualTo("orders");
        }
        assertThat(cancelled).isTrue();
        verify(view).listDatabases(BsonDocument.class);
        verify(view).withCodecRegistry(MongoClientSettings.getDefaultCodecRegistry());
        verify(listing).nameOnly(true);
        verify(listing).authorizedDatabasesOnly(true);
        verify(listing).batchSize(20);
        verify(listing).subscribe(any());
        verify(client, never()).listDatabases(any(Class.class));
        verify(client, never()).close();
    }
}
