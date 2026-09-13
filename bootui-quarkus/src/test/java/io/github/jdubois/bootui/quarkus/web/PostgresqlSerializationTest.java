package io.github.jdubois.bootui.quarkus.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jdubois.bootui.core.dto.PostgresReplicationDto;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PostgresqlSerializationTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replicaAvailabilitySurvivesJackson2Serialization(boolean available) {
        var replication = new PostgresReplicationDto(false, List.of(), null, null, null, null, null, null, available);

        com.fasterxml.jackson.databind.JsonNode json = new ObjectMapper().valueToTree(replication);

        assertThat(json.get("replicasAvailable").isBoolean()).isTrue();
        assertThat(json.get("replicasAvailable").asBoolean()).isEqualTo(available);
        assertThat(json.get("replicas").isArray()).isTrue();
        assertThat(json.get("replicas").size()).isZero();
    }
}
