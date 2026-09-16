package io.github.jdubois.bootui.quarkus.mongodb;

import jakarta.inject.Singleton;
import java.util.List;

/** Arc declaration metadata only; holding this bean cannot retain an application Mongo client. */
@Singleton
public class MongoDbClientsSnapshot {
    private volatile List<Declaration> declarations = List.of();
    private volatile boolean truncated;

    public record Declaration(String beanId, String name, String driverStyle, boolean removed) {
        @io.quarkus.runtime.annotations.RecordableConstructor
        public Declaration {}
    }

    public void install(List<Declaration> declarations, boolean truncated) {
        this.declarations = List.copyOf(declarations);
        this.truncated = truncated;
    }

    public List<Declaration> declarations() {
        return declarations;
    }

    public boolean truncated() {
        return truncated;
    }
}
