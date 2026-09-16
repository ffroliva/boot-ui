package io.github.jdubois.bootui.quarkus.it;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

public class MongoDbLiveProfile implements QuarkusTestProfile {
    @Override
    public String getConfigProfile() {
        return "mongodb-live";
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.mongodb.devservices.enabled",
                "false",
                "quarkus.mongodb.health.enabled",
                "false",
                "bootui.mongodb.timeout-ms",
                "10000",
                "bootui.mongodb.operation-timeout-ms",
                "2000");
    }
}
