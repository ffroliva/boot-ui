package io.github.jdubois.bootui.quarkus.it;

import com.mongodb.client.MongoClient;
import io.quarkus.arc.Unremovable;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import java.util.concurrent.atomic.AtomicInteger;

/** A genuinely lazy application producer, unlike Quarkus's startup synthetic named clients. */
@ApplicationScoped
@IfBuildProfile("mongodb-live")
public class MongoDbLazyClient {
    public static final AtomicInteger CREATIONS = new AtomicInteger();

    @Produces
    @ApplicationScoped
    @Named("lazyCustom")
    @LazyFixture
    @Unremovable
    MongoClient client() {
        CREATIONS.incrementAndGet();
        throw new AssertionError("BootUI must not initialize a lazy application Mongo client");
    }

    @jakarta.inject.Qualifier
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target({
        java.lang.annotation.ElementType.METHOD,
        java.lang.annotation.ElementType.FIELD,
        java.lang.annotation.ElementType.PARAMETER,
        java.lang.annotation.ElementType.TYPE
    })
    public @interface LazyFixture {}
}
