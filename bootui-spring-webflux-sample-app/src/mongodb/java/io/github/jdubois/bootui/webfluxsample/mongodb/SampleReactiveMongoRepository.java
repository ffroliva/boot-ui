package io.github.jdubois.bootui.webfluxsample.mongodb;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Flux;

public interface SampleReactiveMongoRepository extends ReactiveMongoRepository<SampleReactiveMongoProduct, String> {

    Flux<SampleReactiveMongoProduct> findTop3ByCategoryOrderBySkuAsc(String category);
}
