package io.github.jdubois.bootui.sample.mongodb;

import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import io.github.jdubois.bootui.engine.safety.LocalhostGuard;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardConfig;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardDecision;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardRequest;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.Document;

@Path("/api/sample/mongodb")
@Produces(MediaType.APPLICATION_JSON)
public class SampleMongoResource {

    private static final String DATABASE = "bootui_sample";
    private static final String COLLECTION = "sample_mongo_products";
    private final AtomicBoolean running = new AtomicBoolean();

    @Inject
    MongoClient sync;

    @Inject
    ReactiveMongoClient reactive;

    @Inject
    RoutingContext routingContext;

    @GET
    public Map<String, Object> availability() {
        return Map.of("available", true, "driverStyles", new String[] {"sync", "reactive"}, "database", DATABASE);
    }

    @POST
    @Path("/workload")
    @Blocking
    public Response syncWorkload() {
        Response rejection = rejectUnsafeRequest();
        if (rejection != null) {
            return rejection;
        }
        if (!running.compareAndSet(false, true)) {
            return busy();
        }
        try {
            var collection =
                    sync.getDatabase(DATABASE).getCollection(COLLECTION).withTimeout(2, TimeUnit.SECONDS);
            collection.replaceOne(
                    Filters.eq("_id", "quarkus-sync"), product("quarkus-sync"), new ReplaceOptions().upsert(true));
            long read = collection.countDocuments(
                    Filters.eq("_id", "quarkus-sync"),
                    new CountOptions().limit(1).maxTime(2, TimeUnit.SECONDS));
            return completed("sync", read);
        } catch (MongoException failure) {
            return unavailable();
        } finally {
            running.set(false);
        }
    }

    @POST
    @Path("/reactive-workload")
    public Uni<Response> reactiveWorkload() {
        return Uni.createFrom().deferred(() -> {
            Response rejection = rejectUnsafeRequest();
            if (rejection != null) {
                return Uni.createFrom().item(rejection);
            }
            if (!running.compareAndSet(false, true)) {
                return Uni.createFrom().item(busy());
            }
            var collection = reactive.getDatabase(DATABASE).getCollection(COLLECTION);
            return collection
                    .replaceOne(
                            Filters.eq("_id", "quarkus-reactive"),
                            product("quarkus-reactive"),
                            new ReplaceOptions().upsert(true))
                    .chain(() -> collection.countDocuments(
                            Filters.eq("_id", "quarkus-reactive"),
                            new CountOptions().limit(1).maxTime(2, TimeUnit.SECONDS)))
                    .map(count -> completed("reactive", count))
                    .ifNoItem()
                    .after(Duration.ofSeconds(6))
                    .fail()
                    .onFailure()
                    .recoverWithItem(failure -> unavailable())
                    .onTermination()
                    .invoke(() -> running.set(false));
        });
    }

    private static Document product(String id) {
        return new Document("_id", id)
                .append("sku", id)
                .append("category", "quarkus")
                .append("available", true);
    }

    private Response rejectUnsafeRequest() {
        var request = routingContext.request();
        String host = request.getHeader("Host");
        if (host == null && request.authority() != null) {
            host = request.authority().toString();
        }
        var decision = new LocalhostGuard()
                .decide(
                        new LocalhostGuardRequest(
                                request.method().name(),
                                request.remoteAddress() == null
                                        ? null
                                        : request.remoteAddress().hostAddress(),
                                host,
                                request.getHeader("Origin"),
                                request.getHeader("Sec-Fetch-Site")),
                        new LocalhostGuardConfig(false, null, null, null, false, null));
        return decision instanceof LocalhostGuardDecision.Reject rejection
                ? Response.status(403)
                        .entity(Map.of("error", rejection.message()))
                        .build()
                : null;
    }

    private static Response completed(String style, long read) {
        return Response.ok(Map.of("database", DATABASE, "driverStyle", style, "upserted", 1, "productsRead", read))
                .build();
    }

    private static Response busy() {
        return Response.status(429)
                .entity(Map.of("error", "A Mongo sample workload is already running"))
                .build();
    }

    private static Response unavailable() {
        return Response.status(503)
                .entity(Map.of(
                        "error", "Mongo sample workload failed. Check the local fixture and its application account."))
                .build();
    }
}
