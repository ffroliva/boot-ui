package io.github.jdubois.bootui.autoconfigure.mongodb;

import io.github.jdubois.bootui.core.dto.MongoDbReport;
import io.github.jdubois.bootui.engine.mongodb.MongoDbInspectionService;
import io.github.jdubois.bootui.engine.mongodb.MongoDbRequestException;
import io.github.jdubois.bootui.engine.mongodb.MongoDbRequests;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/** Shared MVC/WebFlux transport. The existing WebFlux handler adapter owns blocking dispatch. */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/" + BootUiPanels.MONGODB)
public class MongoDbController {
    private static final ObjectReader INSPECTION_READER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build()
            .readerFor(new TypeReference<Map<String, Object>>() {});

    private final MongoDbInspectionService service;

    public MongoDbController(MongoDbInspectionService service) {
        this.service = service;
    }

    @GetMapping
    public MongoDbReport report(
            @RequestParam(required = false) String snapshotId,
            @RequestParam(required = false) String section,
            @RequestParam(required = false) String databaseId,
            @RequestParam(required = false) String collectionId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) Integer limit,
            @RequestParam org.springframework.util.MultiValueMap<String, String> parameters) {
        MongoDbRequests.reportQuery(parameters);
        return service.report(snapshotId, section, databaseId, collectionId, query, offset, limit);
    }

    public MongoDbReport report() {
        return service.report();
    }

    @PostMapping(value = "/inspect", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MongoDbReport inspect(@RequestBody byte[] request) {
        Map<String, ?> input;
        try {
            input = INSPECTION_READER.readValue(request);
        } catch (JacksonException invalid) {
            throw MongoDbRequests.invalid("Invalid MongoDB inspection request");
        }
        return service.inspect(MongoDbRequests.inspect(input));
    }

    @ExceptionHandler(MongoDbRequestException.class)
    public ResponseEntity<Map<String, String>> invalidRequest(MongoDbRequestException error) {
        return ResponseEntity.status(error.status()).body(Map.of("error", error.getMessage()));
    }
}
