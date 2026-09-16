package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(BootUiQuarkusMongoDbCustomPathLiveTest.CustomPathProfile.class)
@QuarkusTestResource(value = MongoDbLiveResource.class, restrictToAnnotatedClass = true)
class BootUiQuarkusMongoDbCustomPathLiveTest {
    public static final class CustomPathProfile extends MongoDbLiveProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            var config = new java.util.LinkedHashMap<>(super.getConfigOverrides());
            config.put("quarkus.http.root-path", "/host");
            config.put("bootui.path", "/dev-console");
            config.put("bootui.api-path", "/internal/bootui-api");
            return config;
        }
    }

    @TestHTTPResource
    URL baseUrl;

    @Test
    void sharedResourceUsesComposedMountWithoutLeavingDefaultAliases() {
        String origin = baseUrl.getProtocol() + "://" + baseUrl.getAuthority();
        var probe = new BootUiHttpProbe(origin);
        for (String style : java.util.List.of("SYNC", "REACTIVE")) {
            String id = MongoDbLiveAssertions.client(
                            probe.get("/host/internal/bootui-api/mongodb").json(), "named", style)
                    .path("id")
                    .asText();
            io.github.jdubois.bootui.conformance.MongoDbReportContract.verify(
                    probe, origin, "/host/internal/bootui-api", id, "fixture_orders", "fixture_compound");
        }
        assertThat(probe.get("/bootui/api/mongodb").status()).isEqualTo(404);
        assertThat(probe.get("/host/bootui/api/mongodb").status()).isEqualTo(404);
        io.github.jdubois.bootui.conformance.MongoDbProcessContract.browser(origin, "/host/dev-console", true);
    }
}
