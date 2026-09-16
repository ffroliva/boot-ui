package io.github.jdubois.bootui.webfluxsample.mongodb;

import com.mongodb.MongoException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@Profile("mongodb-diagnostics")
@RequestMapping("/api/sample/mongodb")
public class SampleReactiveMongoController {

    private final SampleReactiveMongoRepository repository;
    private final AtomicBoolean running = new AtomicBoolean();

    public SampleReactiveMongoController(SampleReactiveMongoRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    Map<String, Object> availability() {
        return Map.of("available", true, "driverStyle", "reactive", "database", "bootui_sample");
    }

    @GetMapping("/csrf")
    Mono<Map<String, String>> csrf(ServerWebExchange exchange) {
        Mono<CsrfToken> token = exchange.getAttribute(CsrfToken.class.getName());
        return token == null
                ? Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "CSRF token is unavailable"))
                : token.map(value -> Map.of("headerName", value.getHeaderName(), "token", value.getToken()));
    }

    @PostMapping("/workload")
    Mono<Summary> workload() {
        return Mono.defer(() -> {
            if (!running.compareAndSet(false, true)) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS, "A Mongo sample workload is already running"));
            }
            return repository
                    .saveAll(List.of(
                            new SampleReactiveMongoProduct("webflux-one", "WEBFLUX-ONE", "reactive", true),
                            new SampleReactiveMongoProduct("webflux-two", "WEBFLUX-TWO", "reactive", true)))
                    .thenMany(repository.findTop3ByCategoryOrderBySkuAsc("reactive"))
                    .collectList()
                    .map(products -> new Summary("bootui_sample", 2, products.size()))
                    .timeout(Duration.ofSeconds(8))
                    .doFinally(signal -> running.set(false));
        });
    }

    @ExceptionHandler({DataAccessException.class, MongoException.class, TimeoutException.class})
    ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(503)
                .body(Map.of(
                        "error",
                        "Reactive Mongo workload failed. Check the local fixture and its application account."));
    }

    public record Summary(String database, int upserted, int productsRead) {}
}
