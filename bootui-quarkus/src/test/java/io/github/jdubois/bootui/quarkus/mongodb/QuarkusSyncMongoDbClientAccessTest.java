package io.github.jdubois.bootui.quarkus.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.ListDatabasesIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCluster;
import com.mongodb.client.MongoCursor;
import io.github.jdubois.bootui.engine.mongodb.MongoDbReadBudget;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

class QuarkusSyncMongoDbClientAccessTest {
    @Test
    @SuppressWarnings("unchecked")
    void authorizedEnumerationUsesOnlyTheResourceSharingTimeoutViewAndClosesTheCursor() {
        MongoClient client = mock(MongoClient.class);
        MongoCluster view = mock(MongoCluster.class);
        ListDatabasesIterable<BsonDocument> listing = mock(ListDatabasesIterable.class, RETURNS_SELF);
        MongoCursor<BsonDocument> cursor = mock(MongoCursor.class);
        when(client.getTimeout(TimeUnit.MILLISECONDS)).thenReturn(40L);
        when(client.withTimeout(40, TimeUnit.MILLISECONDS)).thenReturn(view);
        when(view.withCodecRegistry(MongoClientSettings.getDefaultCodecRegistry()))
                .thenReturn(view);
        when(view.listDatabases(BsonDocument.class)).thenReturn(listing);
        when(listing.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true);
        when(cursor.next()).thenReturn(BsonDocument.parse("{\"name\":\"orders\"}"));
        try (var names = new QuarkusSyncMongoDbClientAccess(client).databaseNames(new MongoDbReadBudget(1000, 500))) {
            assertThat(names.hasNext()).isTrue();
            assertThat(names.next()).isEqualTo("orders");
        }
        verify(view).listDatabases(BsonDocument.class);
        verify(view).withCodecRegistry(MongoClientSettings.getDefaultCodecRegistry());
        verify(listing).nameOnly(true);
        verify(listing).authorizedDatabasesOnly(true);
        verify(listing).batchSize(20);
        verify(cursor).close();
        verify(client, never()).listDatabases(any(Class.class));
        verify(client, never()).close();
    }

    @Test
    void expiredBudgetDoesNotStartAnUnlimitedOperation() {
        MongoClient client = mock(MongoClient.class);
        AtomicLong ticker = new AtomicLong();
        var budget = new MongoDbReadBudget(10, 5, ticker::get);
        ticker.set(11_000_000);
        assertThatThrownBy(() -> new QuarkusSyncMongoDbClientAccess(client).databaseNames(budget))
                .hasMessage("TIMEOUT");
        verifyNoInteractions(client);
    }
}
