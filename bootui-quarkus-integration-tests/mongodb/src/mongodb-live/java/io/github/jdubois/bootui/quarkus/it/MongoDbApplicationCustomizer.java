package io.github.jdubois.bootui.quarkus.it;

import com.mongodb.MongoClientSettings;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.mongodb.runtime.MongoClientCustomizer;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.atomic.AtomicInteger;

/** Test/application-owned listener, never a BootUI extension contribution. */
@ApplicationScoped
@IfBuildProfile("mongodb-live")
public class MongoDbApplicationCustomizer implements MongoClientCustomizer {
    public static final AtomicInteger CUSTOMIZATIONS = new AtomicInteger();
    public static final AtomicInteger COMMANDS = new AtomicInteger();

    @Override
    public MongoClientSettings.Builder customize(MongoClientSettings.Builder builder) {
        CUSTOMIZATIONS.incrementAndGet();
        return builder.applicationName("fixture-customized");
    }

    // Quarkus discovers CommandListener implementations as CDI beans. A named static class is
    // intentional: anonymous listeners are not valid CDI bean classes during augmentation.
    @ApplicationScoped
    @IfBuildProfile("mongodb-live")
    public static class CommandCounter implements CommandListener {
        @Override
        public void commandStarted(CommandStartedEvent event) {
            COMMANDS.incrementAndGet();
        }
    }
}
