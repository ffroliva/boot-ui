package io.github.jdubois.bootui.quarkus.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jdubois.bootui.core.dto.MongoDbIndexDto;
import io.github.jdubois.bootui.core.dto.MongoDbIndexKeyDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class MongoDbSerializationTest {
    @Test
    void jacksonTwoKeepsNullUnknownsOrderedKeysAndExactIntegerStringsWithoutDriverAnnotations() throws Exception {
        var index = new MongoDbIndexDto(
                "i-1",
                "c-1",
                "d-1",
                "client-1",
                "compound",
                List.of(new MongoDbIndexKeyDto("region", "1"), new MongoDbIndexKeyDto("time", "-1")),
                true,
                null,
                false,
                "9007199254740993",
                true,
                false,
                false,
                false,
                List.of());
        var mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(index);
        assertThat(mapper.readTree(json)).isEqualTo(mapper.readTree("""
                {"id":"i-1","collectionId":"c-1","databaseId":"d-1","clientId":"client-1","name":"compound",
                 "keys":[{"field":"region","kind":"1"},{"field":"time","kind":"-1"}],
                 "unique":true,"sparse":null,"hidden":false,"expireAfterSeconds":"9007199254740993",
                 "partialFilterPresent":true,"collationPresent":false,"wildcardProjectionPresent":false,
                 "hasUnsupportedOptions":false,"limitations":[]}
                """));
        assertThat(json).doesNotContain("$numberLong", "org.bson", "com.mongodb");
    }
}
