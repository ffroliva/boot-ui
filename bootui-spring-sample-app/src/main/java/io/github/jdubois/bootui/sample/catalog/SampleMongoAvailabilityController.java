package io.github.jdubois.bootui.sample.catalog;

import java.util.Map;
import org.springframework.context.ApplicationContext;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sample/mongodb")
class SampleMongoAvailabilityController {

    private final ApplicationContext context;

    SampleMongoAvailabilityController(ApplicationContext context) {
        this.context = context;
    }

    @GetMapping
    Map<String, Object> availability() {
        boolean available = context.containsBean("sampleMongoWorkload");
        return Map.of(
                "available",
                available,
                "message",
                available
                        ? "Mongo documents use bootui_sample. H2 still backs JPA, Flyway and Liquibase."
                        : "Mongo workload unavailable. Start the sample with run-local-mongodb.sh.");
    }

    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }
}
