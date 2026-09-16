package io.github.jdubois.bootui.spi;

import io.github.jdubois.bootui.core.dto.MongoDbCollectionDto;
import io.github.jdubois.bootui.core.dto.MongoDbIndexDto;
import io.github.jdubois.bootui.core.dto.MongoDbSettingDto;
import io.github.jdubois.bootui.engine.mongodb.MongoDbReadBudget;
import java.util.List;

/** Fixed, explicitly invoked metadata operations over an existing application client. */
public interface MongoDbClientAccess {
    List<MongoDbSettingDto> serverInformation(String database, MongoDbReadBudget budget);

    MongoDbCursor<String> databaseNames(MongoDbReadBudget budget);

    MongoDbCursor<String> collectionNames(String database, MongoDbReadBudget budget);

    MongoDbCollectionDto collection(String database, String collection, MongoDbReadBudget budget);

    MongoDbCursor<MongoDbIndexDto> indexes(String database, String collection, MongoDbReadBudget budget);
}
