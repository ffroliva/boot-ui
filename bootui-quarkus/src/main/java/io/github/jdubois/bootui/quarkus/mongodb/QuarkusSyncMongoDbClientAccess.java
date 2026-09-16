package io.github.jdubois.bootui.quarkus.mongodb;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCluster;
import com.mongodb.client.MongoIterable;
import io.github.jdubois.bootui.core.dto.MongoDbCollectionDto;
import io.github.jdubois.bootui.core.dto.MongoDbIndexDto;
import io.github.jdubois.bootui.core.dto.MongoDbSettingDto;
import io.github.jdubois.bootui.engine.mongodb.MongoDbReadBudget;
import io.github.jdubois.bootui.engine.mongodb.MongoDbReadException;
import io.github.jdubois.bootui.spi.MongoDbClientAccess;
import io.github.jdubois.bootui.spi.MongoDbCursor;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;

public final class QuarkusSyncMongoDbClientAccess implements MongoDbClientAccess {
    private final MongoClient client;

    public QuarkusSyncMongoDbClientAccess(MongoClient client) {
        this.client = client;
    }

    private MongoCluster view(MongoDbReadBudget budget) {
        return client.withTimeout(
                        MongoDbDriverValues.timeout(budget, client.getTimeout(TimeUnit.MILLISECONDS)),
                        TimeUnit.MILLISECONDS)
                .withCodecRegistry(MongoClientSettings.getDefaultCodecRegistry());
    }

    @Override
    public List<MongoDbSettingDto> serverInformation(String database, MongoDbReadBudget budget) {
        try {
            String name = database == null ? "admin" : database;
            BsonDocument hello = view(budget)
                    .getDatabase(name)
                    .runCommand(new BsonDocument("hello", new BsonInt32(1)), BsonDocument.class);
            BsonDocument build = view(budget)
                    .getDatabase(name)
                    .runCommand(new BsonDocument("buildInfo", new BsonInt32(1)), BsonDocument.class);
            return MongoDbDriverValues.server(hello, build);
        } catch (RuntimeException error) {
            throw MongoDbDriverValues.failure(error);
        }
    }

    @Override
    public MongoDbCursor<String> databaseNames(MongoDbReadBudget budget) {
        return cursor(
                () -> view(budget)
                        .listDatabases(BsonDocument.class)
                        .nameOnly(true)
                        .authorizedDatabasesOnly(true)
                        .batchSize(20),
                budget,
                document -> MongoDbDriverValues.string(document, "name"));
    }

    @Override
    public MongoDbCursor<String> collectionNames(String database, MongoDbReadBudget budget) {
        return cursor(
                () -> view(budget)
                        .getDatabase(database)
                        .listCollectionNames()
                        .authorizedCollections(true)
                        .batchSize(20),
                budget,
                Function.identity());
    }

    @Override
    public MongoDbCollectionDto collection(String database, String collection, MongoDbReadBudget budget) {
        try (MongoDbCursor<MongoDbCollectionDto> cursor = cursor(
                () -> view(budget)
                        .getDatabase(database)
                        .listCollections(BsonDocument.class)
                        .filter(new BsonDocument("name", new BsonString(collection)))
                        .batchSize(1),
                budget,
                MongoDbDriverValues::collection)) {
            if (!cursor.hasNext()) throw new MongoDbReadException("NAMESPACE_DISAPPEARED");
            return cursor.next();
        }
    }

    @Override
    public MongoDbCursor<MongoDbIndexDto> indexes(String database, String collection, MongoDbReadBudget budget) {
        return cursor(
                () -> view(budget)
                        .getDatabase(database)
                        .getCollection(collection)
                        .listIndexes(BsonDocument.class)
                        .batchSize(20),
                budget,
                MongoDbDriverValues::index);
    }

    private static <S, T> MongoDbCursor<T> cursor(
            Supplier<MongoIterable<S>> source, MongoDbReadBudget budget, Function<S, T> mapper) {
        try {
            budget.remainingMillis();
            return new MongoDbSyncCursor<>(source.get().iterator(), budget, mapper);
        } catch (RuntimeException error) {
            throw MongoDbDriverValues.failure(error);
        }
    }
}
