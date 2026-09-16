package io.github.jdubois.bootui.quarkus.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jdubois.bootui.core.dto.MongoDbInspectRequest;
import io.github.jdubois.bootui.engine.mongodb.MongoDbInspectionService;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MongoDbResourceTest {
    @Test
    void passiveReportWithoutParametersStillWorks() {
        var service = mock(MongoDbInspectionService.class);
        var resource = new MongoDbResource(service, new ObjectMapper());
        assertThat(resource.report(null, null, null, null, null, null, null, uriInfo(Map.of()))
                        .getStatus())
                .isEqualTo(200);
        verify(service).report(null, null, null, null, null, null, null);
        verifyNoMoreInteractions(service);
    }

    @Test
    void boundsAndStrictlyParsesBodiesBeforeServiceInvocation() {
        var service = mock(MongoDbInspectionService.class);
        var resource = new MongoDbResource(service, new ObjectMapper());
        for (String body : new String[] {
            "[]",
            "{\"clientId\":1}",
            "{\"unknown\":\"x\"}",
            "{} {}",
            "{\"clientId\":\"a\",\"clientId\":\"b\"}",
            "{\"clientId\":\"a\",\"client\\u0049d\":\"b\"}",
            "{\"clientId\":\"a\"} null",
            "{\"clientId\":\"a\"} SECRET_SENTINEL",
            "\"SECRET_SENTINEL\"",
            "{\"clientId\":"
        }) {
            assertThat(resource.inspect(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
                            .getStatus())
                    .isEqualTo(400);
        }
        assertThat(resource.inspect(new ByteArrayInputStream(new byte[4097])).getStatus())
                .isEqualTo(413);
        verifyNoInteractions(service);
    }

    @Test
    void selectedIdsAreDelegatedWithoutASeparateServiceOrDriver() {
        var service = mock(MongoDbInspectionService.class);
        var resource = new MongoDbResource(service, new ObjectMapper());
        var parameters = Map.of(
                "snapshotId", "snapshot",
                "section", "INDEXES",
                "databaseId", "database",
                "collectionId", "collection",
                "query", "compound",
                "offset", "2",
                "limit", "20");
        assertThat(resource.report(
                                "snapshot",
                                "INDEXES",
                                "database",
                                "collection",
                                "compound",
                                "2",
                                "20",
                                uriInfo(parameters))
                        .getStatus())
                .isEqualTo(200);
        verify(service).report("snapshot", "INDEXES", "database", "collection", "compound", 2, 20);
        String body = " \n{\"clientId\":\"client\",\"scope\":\"SELECTED\",\"databaseId\":\"database\","
                + "\"collectionId\":\"collection\",\"snapshotId\":\"snapshot\"}\r\n ";
        assertThat(resource.inspect(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
                        .getStatus())
                .isEqualTo(200);
        verify(service).inspect(new MongoDbInspectRequest("client", "SELECTED", "database", "collection", "snapshot"));
        verifyNoMoreInteractions(service);
    }

    @Test
    void invalidPageNumbersUseJson400InsteadOfTheJaxRsParameter404() {
        var service = mock(MongoDbInspectionService.class);
        var resource = new MongoDbResource(service, new ObjectMapper());
        assertThat(resource.report(
                                null,
                                null,
                                null,
                                null,
                                null,
                                "not-an-integer",
                                null,
                                uriInfo(Map.of("offset", "not-an-integer")))
                        .getStatus())
                .isEqualTo(400);
        verifyNoInteractions(service);
    }

    @Test
    void unknownReportFieldsAreRejectedBeforeServiceInvocation() {
        var service = mock(MongoDbInspectionService.class);
        var resource = new MongoDbResource(service, new ObjectMapper());
        for (String field : new String[] {"command", "uri", "clientId", "scope", "database"}) {
            var response = resource.report(null, null, null, null, null, null, null, uriInfo(Map.of(field, "")));
            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(response.getEntity()).isEqualTo(Map.of("error", "Unexpected MongoDB report fields"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void duplicateReportFieldsAreRejectedBeforeServiceInvocation() {
        var service = mock(MongoDbInspectionService.class);
        var parameters = uriInfo(Map.of("section", "DATABASES"));
        parameters.getQueryParameters().add("section", "INDEXES");
        var response = new MongoDbResource(service, new ObjectMapper())
                .report(null, "DATABASES", null, null, null, null, null, parameters);
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getEntity()).isEqualTo(Map.of("error", "Duplicate MongoDB report fields"));
        verifyNoInteractions(service);
    }

    @Test
    void strictReaderDoesNotChangeTheApplicationMapper() throws Exception {
        var service = mock(MongoDbInspectionService.class);
        var mapper = new ObjectMapper();
        var resource = new MongoDbResource(service, mapper);
        String body = "{\"clientId\":\"client\",\"clientId\":\"SECRET_SENTINEL\"}";
        var response = resource.inspect(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getEntity()).isEqualTo(Map.of("error", "Invalid MongoDB inspection request"));
        assertThat(mapper.readTree(body).get("clientId").asText()).isEqualTo("SECRET_SENTINEL");
        verifyNoInteractions(service);
    }

    private static UriInfo uriInfo(Map<String, String> parameters) {
        var uriInfo = mock(UriInfo.class);
        var query = new MultivaluedHashMap<String, String>();
        parameters.forEach(query::putSingle);
        when(uriInfo.getQueryParameters()).thenReturn(query);
        return uriInfo;
    }
}
