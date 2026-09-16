package io.github.jdubois.bootui.sample.mongodb;

import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("sample_mongo_products")
@CompoundIndex(name = "category_available", def = "{'category': 1, 'available': 1}")
public record SampleMongoProduct(
        @Id String id,
        @Indexed(name = "sku_unique", unique = true) String sku,

        @Indexed(name = "name_partial", partialFilter = "{'category': 'BOOTUI_PARTIAL_LITERAL_MUST_NOT_LEAK'}")
        String name,

        String category,
        boolean available,
        Details details,

        @Indexed(name = "external_reference_sparse", sparse = true)
        String externalReference) {

    public record Details(String material, List<String> tags) {}
}
