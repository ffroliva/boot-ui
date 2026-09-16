package io.github.jdubois.bootui.sample.mongodb;

import java.util.List;
import java.util.concurrent.Semaphore;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("docker-mongodb")
public class SampleMongoWorkload {

    private final SampleMongoProductRepository repository;
    private final Semaphore running = new Semaphore(1);

    public SampleMongoWorkload(SampleMongoProductRepository repository) {
        this.repository = repository;
    }

    public Summary run() {
        if (!running.tryAcquire()) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "A Mongo sample workload is already running");
        }
        try {
            repository.saveAll(List.of(
                    product(
                            "starter",
                            "BOOTUI-STARTER",
                            "Document starter",
                            "tools",
                            true,
                            "digital",
                            "sample-starter"),
                    product("guide", "BOOTUI-GUIDE", "Document guide", "books", true, "paper", null),
                    product("kit", "BOOTUI-KIT", "Document kit", "tools", false, "digital", null)));
            int available = repository
                    .findTop3ByIdInAndAvailableTrueOrderBySkuAsc(List.of("starter", "guide", "kit"))
                    .size();
            return new Summary(MongoSampleConfiguration.DATABASE, 3, available, repository.countSampleCategories());
        } finally {
            running.release();
        }
    }

    private static SampleMongoProduct product(
            String id, String sku, String name, String category, boolean available, String material, String reference) {
        return new SampleMongoProduct(
                id,
                sku,
                name,
                category,
                available,
                new SampleMongoProduct.Details(material, List.of("sample")),
                reference);
    }

    public record Summary(
            String database,
            int upserted,
            int availableProducts,
            List<SampleMongoProductRepository.CategoryCount> categories) {}
}
