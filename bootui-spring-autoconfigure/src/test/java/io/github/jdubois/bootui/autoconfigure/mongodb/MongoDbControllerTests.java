package io.github.jdubois.bootui.autoconfigure.mongodb;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.core.dto.MongoDbInspectRequest;
import io.github.jdubois.bootui.engine.mongodb.MongoDbInspectionService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class MongoDbControllerTests {
    @Test
    void passiveReportWithoutParametersStillWorksOnBothStacks() throws Exception {
        var service = mock(MongoDbInspectionService.class);
        standaloneSetup(new MongoDbController(service))
                .build()
                .perform(get("/bootui/api/mongodb"))
                .andExpect(status().isOk());
        WebTestClient.bindToController(new MongoDbController(service))
                .build()
                .get()
                .uri("/bootui/api/mongodb")
                .exchange()
                .expectStatus()
                .isOk();
        verify(service, times(2)).report(null, null, null, null, null, null, null);
        verifyNoMoreInteractions(service);
    }

    @Test
    void retainedSelectorsArePassedToTheSharedServiceWithoutInspection() throws Exception {
        var service = mock(MongoDbInspectionService.class);
        standaloneSetup(new MongoDbController(service))
                .build()
                .perform(get("/bootui/api/mongodb")
                        .param("snapshotId", "snapshot")
                        .param("section", "INDEXES")
                        .param("databaseId", "database")
                        .param("collectionId", "collection")
                        .param("query", "ordered")
                        .param("offset", "2")
                        .param("limit", "10"))
                .andExpect(status().isOk());
        WebTestClient.bindToController(new MongoDbController(service))
                .build()
                .get()
                .uri("/bootui/api/mongodb?snapshotId=snapshot&section=INDEXES&databaseId=database"
                        + "&collectionId=collection&query=ordered&offset=2&limit=10")
                .exchange()
                .expectStatus()
                .isOk();
        verify(service, times(2)).report("snapshot", "INDEXES", "database", "collection", "ordered", 2, 10);
        verifyNoMoreInteractions(service);
    }

    @Test
    void unknownReportFieldsAreRejectedOnBothStacksBeforeServiceInvocation() throws Exception {
        var service = mock(MongoDbInspectionService.class);
        var mvc = standaloneSetup(new MongoDbController(service)).build();
        var reactive =
                WebTestClient.bindToController(new MongoDbController(service)).build();
        for (String field : new String[] {"command", "uri", "clientId", "scope", "database"}) {
            mvc.perform(get("/bootui/api/mongodb").param(field, ""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Unexpected MongoDB report fields"));
            reactive.get()
                    .uri(builder -> builder.path("/bootui/api/mongodb")
                            .queryParam(field, "")
                            .build())
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody()
                    .jsonPath("$.error")
                    .isEqualTo("Unexpected MongoDB report fields");
        }
        verifyNoInteractions(service);
    }

    @Test
    void duplicateReportFieldsAreRejectedOnBothStacks() throws Exception {
        var service = mock(MongoDbInspectionService.class);
        standaloneSetup(new MongoDbController(service))
                .build()
                .perform(get("/bootui/api/mongodb").param("section", "DATABASES", "INDEXES"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Duplicate MongoDB report fields"));
        WebTestClient.bindToController(new MongoDbController(service))
                .build()
                .get()
                .uri("/bootui/api/mongodb?section=DATABASES&section=INDEXES")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.error")
                .isEqualTo("Duplicate MongoDB report fields");
        verifyNoInteractions(service);
    }

    @Test
    void actionRejectsUnknownFieldsAndWrongTypesBeforeService() throws Exception {
        var service = mock(MongoDbInspectionService.class);
        var mvc = standaloneSetup(new MongoDbController(service)).build();
        var reactive =
                WebTestClient.bindToController(new MongoDbController(service)).build();
        for (String body : new String[] {
            "{\"clientId\":\"client\",\"command\":\"dropDatabase\"}",
            "{\"clientId\":7}",
            "{\"clientId\":\"client\",\"scope\":\"UNKNOWN\"}"
        }) {
            mvc.perform(post("/bootui/api/mongodb/inspect")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
            reactive.post()
                    .uri("/bootui/api/mongodb/inspect")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body.getBytes(StandardCharsets.UTF_8))
                    .exchange()
                    .expectStatus()
                    .isBadRequest();
        }
        verifyNoInteractions(service);
    }

    @Test
    void bothStacksRejectDuplicateKeysAndTrailingJsonWithoutEchoingTheBody() throws Exception {
        var service = mock(MongoDbInspectionService.class);
        var mvc = standaloneSetup(new MongoDbController(service)).build();
        var reactive =
                WebTestClient.bindToController(new MongoDbController(service)).build();
        for (String body : new String[] {
            "{\"clientId\":\"client\",\"clientId\":\"SECRET_SENTINEL\"}",
            "{\"clientId\":\"client\",\"client\\u0049d\":\"SECRET_SENTINEL\"}",
            "{\"clientId\":\"client\"} {}",
            "{\"clientId\":\"client\"} null",
            "{\"clientId\":\"client\"} SECRET_SENTINEL",
            "[]",
            "\"SECRET_SENTINEL\"",
            "{\"clientId\":"
        }) {
            mvc.perform(post("/bootui/api/mongodb/inspect")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Invalid MongoDB inspection request"));
            reactive.post()
                    .uri("/bootui/api/mongodb/inspect")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body.getBytes(StandardCharsets.UTF_8))
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody()
                    .jsonPath("$.error")
                    .isEqualTo("Invalid MongoDB inspection request");
        }
        verifyNoInteractions(service);
    }

    @Test
    void bothStacksAcceptOneObjectAndForwardEveryInspectionSelector() throws Exception {
        var service = mock(MongoDbInspectionService.class);
        String body = " \n{\"clientId\":\"client\",\"scope\":\"SELECTED\",\"databaseId\":\"database\","
                + "\"collectionId\":\"collection\",\"snapshotId\":\"snapshot\"}\r\n ";
        standaloneSetup(new MongoDbController(service))
                .build()
                .perform(post("/bootui/api/mongodb/inspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        WebTestClient.bindToController(new MongoDbController(service))
                .build()
                .post()
                .uri("/bootui/api/mongodb/inspect")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body.getBytes(StandardCharsets.UTF_8))
                .exchange()
                .expectStatus()
                .isOk();
        verify(service, times(2))
                .inspect(new MongoDbInspectRequest("client", "SELECTED", "database", "collection", "snapshot"));
        verifyNoMoreInteractions(service);
    }
}
