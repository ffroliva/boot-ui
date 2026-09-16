package io.github.jdubois.bootui.quarkus.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jdubois.bootui.engine.mongodb.MongoDbInspectionService;
import io.github.jdubois.bootui.engine.mongodb.MongoDbRequestException;
import io.github.jdubois.bootui.engine.mongodb.MongoDbRequests;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

@ApplicationScoped
@Path("/bootui/api/" + BootUiPanels.MONGODB)
@Produces(MediaType.APPLICATION_JSON)
public class MongoDbResource {
    private final MongoDbInspectionService service;
    private final ObjectMapper mapper;

    @Inject
    public MongoDbResource(MongoDbInspectionService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GET
    public Response report(
            @QueryParam("snapshotId") String snapshotId,
            @QueryParam("section") String section,
            @QueryParam("databaseId") String databaseId,
            @QueryParam("collectionId") String collectionId,
            @QueryParam("query") String query,
            @QueryParam("offset") String offset,
            @QueryParam("limit") String limit,
            @Context UriInfo uriInfo) {
        try {
            MongoDbRequests.reportQuery(uriInfo.getQueryParameters());
            return Response.ok(service.report(
                            snapshotId, section, databaseId, collectionId, query, number(offset), number(limit)))
                    .build();
        } catch (MongoDbRequestException invalid) {
            return error(invalid.status(), invalid.getMessage());
        } catch (NumberFormatException invalid) {
            return error(400, "Invalid MongoDB page range");
        }
    }

    @POST
    @Path("/inspect")
    @Blocking
    @Consumes(MediaType.APPLICATION_JSON)
    public Response inspect(InputStream body) {
        try {
            byte[] bytes = body.readNBytes(4097);
            if (bytes.length > 4096) return error(413, "MongoDB request is too large");
            Map<String, ?> input = mapper.readerFor(new TypeReference<Map<String, Object>>() {})
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .readValue(bytes);
            return Response.ok(service.inspect(MongoDbRequests.inspect(input))).build();
        } catch (MongoDbRequestException invalid) {
            return error(invalid.status(), invalid.getMessage());
        } catch (IOException | IllegalArgumentException invalid) {
            return error(400, "Invalid MongoDB inspection request");
        }
    }

    private static Response error(int status, String message) {
        return Response.status(status)
                .entity(Map.of("error", message))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    private static Integer number(String value) {
        return value == null ? null : Integer.valueOf(value);
    }
}
