package io.github.jdubois.bootui.autoconfigure.mongodb;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.cursor.TimeoutMode;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCluster;
import io.github.jdubois.bootui.core.dto.*;
import io.github.jdubois.bootui.engine.mongodb.MongoDbMessages;
import io.github.jdubois.bootui.engine.mongodb.MongoDbReadBudget;
import io.github.jdubois.bootui.engine.mongodb.MongoDbReadException;
import io.github.jdubois.bootui.spi.MongoDbClientAccess;
import io.github.jdubois.bootui.spi.MongoDbCursor;
import io.github.jdubois.bootui.spi.MongoDbProvider;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.reactivestreams.Publisher;

public final class SpringReactiveMongoDbClientAccess implements MongoDbClientAccess {
    private final MongoClient client;

    public SpringReactiveMongoDbClientAccess(MongoClient client) {
        this.client = client;
    }

    public static MongoDbClientDeclarations.Reader reader() {
        return new MongoDbClientDeclarations.Reader() {
            public Class<?> clientType() {
                return MongoClient.class;
            }

            public String style() {
                return "REACTIVE";
            }

            public boolean supports(Object value) {
                return value.getClass().getName().equals("com.mongodb.reactivestreams.client.internal.MongoClientImpl");
            }

            public MongoDbProvider.Client initialized(String key, String name, Object value, List<String> databases) {
                MongoClient client = (MongoClient) value;
                Long timeout = client.getTimeout(TimeUnit.MILLISECONDS);
                return new MongoDbProvider.Client(
                        key,
                        name,
                        style(),
                        MongoClient.class.getPackage().getImplementationVersion(),
                        "INITIALIZED",
                        databases,
                        timeout == null
                                ? List.of()
                                : List.of(new MongoDbSettingDto(
                                        "operationTimeoutMillis", timeout.toString(), "OBSERVED", "existing client")),
                        MongoDbBsonMetadata.topology(client.getClusterDescription()),
                        databases.isEmpty() ? List.of(MongoDbMessages.limitation("NO_CONFIGURED_DATABASE")) : List.of(),
                        client,
                        new SpringReactiveMongoDbClientAccess(client));
            }
        };
    }

    private MongoCluster view(MongoDbReadBudget budget) {
        int timeout = budget.operationMillis();
        Long applicationTimeout = client.getTimeout(TimeUnit.MILLISECONDS);
        if (applicationTimeout != null && applicationTimeout > 0) timeout = (int) Math.min(timeout, applicationTimeout);
        return client.withTimeout(timeout, TimeUnit.MILLISECONDS)
                .withCodecRegistry(MongoClientSettings.getDefaultCodecRegistry());
    }

    @Override
    public List<MongoDbSettingDto> serverInformation(String database, MongoDbReadBudget budget) {
        return safe(() -> {
            String target = database == null ? "admin" : database;
            BsonDocument hello = one(
                    view(budget)
                            .getDatabase(target)
                            .runCommand(new BsonDocument("hello", new BsonInt32(1)), BsonDocument.class),
                    budget);
            BsonDocument build = one(
                    view(budget)
                            .getDatabase(target)
                            .runCommand(new BsonDocument("buildInfo", new BsonInt32(1)), BsonDocument.class),
                    budget);
            return MongoDbBsonMetadata.server(hello, build);
        });
    }

    @Override
    public MongoDbCursor<String> databaseNames(MongoDbReadBudget budget) {
        return safe(() -> new MongoDbReactiveCursor<>(
                view(budget)
                        .listDatabases(BsonDocument.class)
                        .nameOnly(true)
                        .authorizedDatabasesOnly(true)
                        .batchSize(20),
                document -> MongoDbBsonMetadata.text(document.get("name")),
                budget));
    }

    @Override
    public MongoDbCursor<String> collectionNames(String database, MongoDbReadBudget budget) {
        return safe(() -> new MongoDbReactiveCursor<>(
                view(budget)
                        .getDatabase(database)
                        .listCollectionNames()
                        .authorizedCollections(true)
                        .batchSize(20),
                Function.identity(),
                budget));
    }

    @Override
    public MongoDbCollectionDto collection(String database, String collection, MongoDbReadBudget budget) {
        return safe(() -> MongoDbBsonMetadata.collection(one(
                view(budget)
                        .getDatabase(database)
                        .listCollections(BsonDocument.class)
                        .filter(new BsonDocument("name", new BsonString(collection)))
                        .batchSize(1)
                        .timeoutMode(TimeoutMode.CURSOR_LIFETIME),
                budget)));
    }

    @Override
    public MongoDbCursor<MongoDbIndexDto> indexes(String database, String collection, MongoDbReadBudget budget) {
        return safe(() -> new MongoDbReactiveCursor<>(
                view(budget)
                        .getDatabase(database)
                        .getCollection(collection)
                        .listIndexes(BsonDocument.class)
                        .batchSize(20)
                        .timeoutMode(TimeoutMode.CURSOR_LIFETIME),
                MongoDbBsonMetadata::index,
                budget));
    }

    private static <T> T one(Publisher<T> publisher, MongoDbReadBudget budget) {
        try (MongoDbCursor<T> cursor = new MongoDbReactiveCursor<>(publisher, Function.identity(), budget)) {
            if (!cursor.hasNext()) throw new MongoDbReadException("NAMESPACE_DISAPPEARED");
            return cursor.next();
        }
    }

    private static <T> T safe(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RuntimeException ex) {
            throw MongoDbBsonMetadata.failure(ex);
        }
    }
}
