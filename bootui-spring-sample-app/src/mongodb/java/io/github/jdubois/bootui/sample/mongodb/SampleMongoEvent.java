package io.github.jdubois.bootui.sample.mongodb;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("sample_mongo_events")
public record SampleMongoEvent(
        @Id String id,
        @Indexed(name = "event_expiry", expireAfter = "1h") Instant createdAt,
        String kind) {}
