package io.github.jdubois.bootui.autoconfigure.mongodb;

import io.github.jdubois.bootui.spi.MongoDbProvider;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;

public final class SpringMongoDbProvider implements MongoDbProvider {
    private final ConfigurableListableBeanFactory factory;
    private final List<MongoDbClientDeclarations.Reader> readers;
    private final Map<String, List<String>> scopes;

    public SpringMongoDbProvider(
            ConfigurableListableBeanFactory factory,
            List<MongoDbClientDeclarations.Reader> readers,
            Environment environment) {
        this.factory = factory;
        this.readers = List.copyOf(readers);
        this.scopes = readers.isEmpty() ? Map.of() : MongoDbClientDeclarations.scopes(environment);
    }

    @Override
    public Discovery discover(int maxClients) {
        return MongoDbClientDeclarations.discover(factory, readers, scopes, maxClients);
    }
}
