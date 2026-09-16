package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.client.MongoClients;
import io.github.jdubois.bootui.engine.mongodb.MongoDbReadBudget;
import io.github.jdubois.bootui.quarkus.mongodb.QuarkusReactiveMongoDbClientAccess;
import io.github.jdubois.bootui.quarkus.mongodb.QuarkusSyncMongoDbClientAccess;
import io.github.jdubois.bootui.spi.MongoDbClientAccess;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import org.bson.Document;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(MongoDbLiveProfile.class)
@QuarkusTestResource(value = MongoDbLiveResource.class, restrictToAnnotatedClass = true)
class BootUiQuarkusMongoDbTimeoutLiveTest {
    @Inject
    MongoDbLiveClients clients;

    @Inject
    Config config;

    @Test
    void nativeSyncAndReactiveTimeoutsCloseOwnedCursorsAndLeaveApplicationClientsUsable() {
        try (var fixtureAdmin =
                MongoClients.create(config.getValue("bootui.fixture.mongo-admin-password-uri", String.class))) {
            var admin = fixtureAdmin.getDatabase("admin");
            List<MongoDbClientAccess> paths = List.of(
                    new QuarkusSyncMongoDbClientAccess(clients.timeoutSync()),
                    new QuarkusReactiveMongoDbClientAccess(
                            clients.timeoutReactive().unwrap()));
            for (var access : paths) {
                long baselineCursors = openCursors(admin);
                admin.runCommand(new Document("configureFailPoint", "failCommand")
                        .append("mode", "alwaysOn")
                        .append(
                                "data",
                                new Document("failCommands", List.of("listCollections"))
                                        .append("appName", "bootui-timeout-fixture")
                                        .append("blockConnection", true)
                                        .append("blockTimeMS", 1000)));
                try {
                    assertThatThrownBy(() -> {
                                try (var names = access.collectionNames(
                                        MongoDbLiveResource.DATABASE, new MongoDbReadBudget(500, 100))) {
                                    names.hasNext();
                                }
                            })
                            .hasMessage("TIMEOUT")
                            .hasNoCause();
                } finally {
                    admin.runCommand(new Document("configureFailPoint", "failCommand").append("mode", "off"));
                }
                try (var names =
                        access.collectionNames(MongoDbLiveResource.DATABASE, new MongoDbReadBudget(5000, 2000))) {
                    assertThat(names.hasNext()).isTrue();
                }
                // >20 names ensures the driver has an owned server cursor; close at a partial first batch.
                try (var names =
                        access.collectionNames(MongoDbLiveResource.DATABASE, new MongoDbReadBudget(5000, 2000))) {
                    assertThat(names.next()).isNotBlank();
                }
                awaitCursorCleanup(admin, baselineCursors);
            }
        }
    }

    private static long openCursors(com.mongodb.client.MongoDatabase admin) {
        var status = admin.runCommand(new Document("serverStatus", 1));
        return ((Number) status.get("metrics", Document.class)
                        .get("cursor", Document.class)
                        .get("open", Document.class)
                        .get("total"))
                .longValue();
    }

    private static void awaitCursorCleanup(com.mongodb.client.MongoDatabase admin, long baseline) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (openCursors(admin) > baseline && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while observing fixture cursor cleanup");
            }
        }
        assertThat(openCursors(admin)).isLessThanOrEqualTo(baseline);
    }
}
