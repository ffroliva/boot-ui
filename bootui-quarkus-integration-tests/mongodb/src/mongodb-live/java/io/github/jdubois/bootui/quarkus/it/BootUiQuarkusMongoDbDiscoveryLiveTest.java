package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.engine.mongodb.MongoDbReadBudget;
import io.github.jdubois.bootui.quarkus.mongodb.QuarkusReactiveMongoDbClientAccess;
import io.github.jdubois.bootui.spi.MongoDbProvider;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.net.URL;
import org.bson.Document;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(MongoDbLiveProfile.class)
@QuarkusTestResource(value = MongoDbLiveResource.class, restrictToAnnotatedClass = true)
class BootUiQuarkusMongoDbDiscoveryLiveTest {
    @TestHTTPResource
    URL baseUrl;

    @Inject
    MongoDbLiveClients clients;

    @Inject
    MongoDbProvider provider;

    @Test
    void passiveReadsPreserveNativeInstancesAndLeaveLazyAndInactiveClientsUntouched() {
        clients.sync().getDatabase(MongoDbLiveResource.DATABASE).runCommand(new Document("ping", 1));
        new QuarkusReactiveMongoDbClientAccess(clients.reactive().unwrap())
                .serverInformation(MongoDbLiveResource.DATABASE, new MongoDbReadBudget(3000, 1000));
        int customizations = MongoDbApplicationCustomizer.CUSTOMIZATIONS.get();
        int commands = MongoDbApplicationCustomizer.COMMANDS.get();
        assertThat(customizations).isGreaterThanOrEqualTo(2);
        var inventory = provider.discover(64);
        assertThat(inventory.clients())
                .anyMatch(client -> client.name().equals("syncOnly")
                        && client.driverStyle().equals("SYNC")
                        && client.access() != null);
        assertThat(inventory.clients())
                .anyMatch(client -> client.name().equals("reactiveOnly")
                        && client.driverStyle().equals("REACTIVE")
                        && client.access() != null);
        // The no-BootUI packaged control proves native Quarkus startup creates both styles for
        // every declared named client, even unused declarations. Do not blame BootUI for those.
        // Assert the meaningful invariant: inventory and tool discovery create no additional objects.
        var identities = new java.util.LinkedHashMap<String, Object>();
        inventory.clients().forEach(client -> identities.put(client.key(), client.identity()));
        assertThat(inventory.clients())
                .filteredOn(client ->
                        client.name().equals("inactive") || client.name().equals("lazyCustom"))
                .isNotEmpty()
                .allMatch(client -> client.access() == null);
        assertThat(inventory.clients()).anyMatch(client -> client.name().equals("lazyCustom"));
        assertThat(MongoDbLazyClient.CREATIONS).hasValue(0);
        var probe = new BootUiHttpProbe(baseUrl.toExternalForm());
        assertThat(probe.get("/bootui/api/panels").status()).isEqualTo(200);
        assertThat(probe.get("/bootui/api/mongodb").status()).isEqualTo(200);
        assertThat(probe.get("/bootui/api/mongodb").status()).isEqualTo(200);
        assertThat(probe.get("/bootui/api/cli").status()).isEqualTo(200);
        assertThat(probe.request(
                                "POST",
                                "/bootui/api/mcp",
                                MongoDbLiveAssertions.JSON,
                                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
                        .status())
                .isEqualTo(200);
        io.github.jdubois.bootui.conformance.MongoDbProcessContract.browser(
                baseUrl.toExternalForm().replaceAll("/$", ""), "/bootui", false);
        assertThat(MongoDbApplicationCustomizer.CUSTOMIZATIONS.get()).isEqualTo(customizations);
        assertThat(MongoDbApplicationCustomizer.COMMANDS.get()).isEqualTo(commands);
        var after = provider.discover(64).clients();
        assertThat(after).hasSize(identities.size());
        after.forEach(client -> assertThat(client.identity()).isSameAs(identities.get(client.key())));
        assertThat(MongoDbLazyClient.CREATIONS).hasValue(0);
    }
}
