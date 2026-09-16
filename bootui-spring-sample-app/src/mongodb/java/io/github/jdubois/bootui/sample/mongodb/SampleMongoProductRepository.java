package io.github.jdubois.bootui.sample.mongodb;

import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SampleMongoProductRepository extends MongoRepository<SampleMongoProduct, String> {

    List<SampleMongoProduct> findTop3ByIdInAndAvailableTrueOrderBySkuAsc(List<String> ids);

    @Aggregation(
            pipeline = {
                "{'$match': {'_id': {'$in': ['starter', 'guide', 'kit']}, 'available': true}}",
                "{'$group': {'_id': '$category', 'count': {'$sum': 1}}}",
                "{'$sort': {'_id': 1}}",
                "{'$limit': 3}"
            })
    List<CategoryCount> countSampleCategories();

    record CategoryCount(@Id String category, long count) {}
}
