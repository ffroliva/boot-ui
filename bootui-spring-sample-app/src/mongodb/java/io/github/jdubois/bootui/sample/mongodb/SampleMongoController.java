package io.github.jdubois.bootui.sample.mongodb;

import com.mongodb.MongoException;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("docker-mongodb")
@RequestMapping("/api/sample/mongodb")
public class SampleMongoController {

    private final SampleMongoWorkload workload;

    public SampleMongoController(SampleMongoWorkload workload) {
        this.workload = workload;
    }

    @PostMapping("/workload")
    SampleMongoWorkload.Summary workload() {
        return workload.run();
    }

    @ExceptionHandler({DataAccessException.class, MongoException.class})
    ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(503)
                .body(Map.of(
                        "error", "Mongo sample workload failed. Check the local fixture and its application account."));
    }
}
