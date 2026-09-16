package io.github.jdubois.bootui.webfluxsample.mongodb;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("sample_mongo_products")
public record SampleReactiveMongoProduct(
        @Id String id,
        @Indexed(name = "sku_unique", unique = true) String sku,
        String category,
        boolean available) {}
