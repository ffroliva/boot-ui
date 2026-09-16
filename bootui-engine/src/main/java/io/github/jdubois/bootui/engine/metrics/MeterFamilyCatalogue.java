package io.github.jdubois.bootui.engine.metrics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BootUI's curated, versioned catalogue of well-known Micrometer meter families.
 *
 * <p>The catalogue answers "which integration registered this meter family, and what do its measurements
 * mean?" from the meter <em>name</em> alone. It is deliberately conservative: it only claims a contributor
 * for naming conventions that Spring Boot, Micrometer, Quarkus and common integrations own, and every meter
 * that does not match a family stays in the explicit {@link #APPLICATION_GROUP_ID application/unclassified}
 * group rather than receiving a guessed provenance.</p>
 *
 * <p>The data lives in Java rather than a resource file so that the engine stays JSON-free, the catalogue is
 * compiled and reviewable with the code that uses it, and Quarkus native images need no resource registration.
 * {@link #VERSION} changes whenever a family is added, renamed or re-scoped, and is echoed in the browser
 * contract so a curated explanation can always be traced back to a catalogue revision.</p>
 *
 * <p>Nothing here reads meter values, tag values, or the classpath: the catalogue registers no meter, starts no
 * binder and performs no I/O.</p>
 */
public final class MeterFamilyCatalogue {

    /** Version of the curated data below. Bump on every family addition, rename or re-scope. */
    public static final String VERSION = "2026.2";

    /** Group holding every meter BootUI cannot attribute to a known integration. */
    public static final String APPLICATION_GROUP_ID = "application";

    private static final MeterGroup APPLICATION_GROUP = new MeterGroup(
            APPLICATION_GROUP_ID,
            "Application / unclassified",
            "Application or unrecognized instrumentation",
            "Meters whose names match no known integration convention. They are usually registered by this"
                    + " application or by a library BootUI does not recognize.",
            "BootUI does not invent documentation for these meters: any description shown here comes from the"
                    + " registry itself, and an absent description simply means none was registered.");

    private static final List<MeterGroup> GROUPS = List.of(
            new MeterGroup(
                    "jvm",
                    "JVM",
                    "Micrometer JVM binders",
                    "Memory pools, garbage collection, threads, class loading and buffer pools reported by the JVM"
                            + " itself.",
                    "Gauges are point-in-time readings, while counters and timers accumulate for the whole process"
                            + " lifetime, so read them as totals since start rather than as a current rate."),
            new MeterGroup(
                    "process",
                    "Process",
                    "Micrometer process binders",
                    "CPU usage, uptime, start time and file descriptors for this operating-system process.",
                    "CPU gauges are fractions between 0 and 1 sampled when the meter is read; uptime and start time"
                            + " are seconds, so a rising uptime with a constant start time is the expected steady"
                            + " state."),
            new MeterGroup(
                    "system",
                    "System",
                    "Micrometer system binders",
                    "Host-level CPU, load average and disk space visible to the JVM.",
                    "These gauges describe the machine rather than the application, so compare them against process"
                            + " gauges before blaming the application for host-wide pressure."),
            new MeterGroup(
                    "http-server",
                    "HTTP server",
                    "Framework HTTP server instrumentation (Spring MVC/WebFlux or Quarkus/Vert.x)",
                    "Inbound HTTP throughput, latency and outcome for the endpoints this application serves.",
                    "Request timers expose count, total time and a rolling maximum: divide the total by the count for"
                            + " a mean, and always compare per URI template and status rather than in aggregate."),
            new MeterGroup(
                    "http-client",
                    "HTTP client",
                    "Framework HTTP client instrumentation",
                    "Outbound HTTP calls this application makes to other services.",
                    "Client timers are tagged with the target URI template and outcome, so a rising count with a flat"
                            + " total time usually means cheap retries rather than slow dependencies."),
            new MeterGroup(
                    "datasource",
                    "Datasource pools",
                    "Connection-pool instrumentation (HikariCP, Agroal or Micrometer JDBC)",
                    "Connection-pool size, usage, acquisition timing and pending requests for JDBC datasources.",
                    "Pool gauges are instantaneous; sustained pending or timeout counts next to a saturated active"
                            + " gauge indicate pool exhaustion rather than slow SQL."),
            new MeterGroup(
                    "cache",
                    "Caches",
                    "Micrometer cache binders",
                    "Cache requests, hits, misses, puts, evictions and load timings published by cache providers.",
                    "These counters are cumulative for the cache's lifetime, so derive a hit ratio from hits and"
                            + " misses over the same window instead of reading a single counter in isolation."),
            new MeterGroup(
                    "messaging",
                    "Messaging",
                    "Messaging client instrumentation (Kafka, RabbitMQ/AMQP or JMS)",
                    "Producer, consumer and listener activity for message brokers this application talks to.",
                    "Broker client meters mix cumulative counters with client-computed rate gauges, so check the base"
                            + " unit before treating a value as a total."),
            new MeterGroup(
                    "resilience",
                    "Resilience",
                    "Fault-tolerance instrumentation (Resilience4j or SmallRye Fault Tolerance)",
                    "Circuit breakers, retries, bulkheads, rate limiters and timeouts guarding calls.",
                    "Call counters are tagged by outcome and state: a rising failed or not-permitted count with a"
                            + " steady success count is the signal, not the raw total."),
            new MeterGroup(
                    "grpc",
                    "gRPC",
                    "gRPC server and client instrumentation",
                    "gRPC call counts, latency and message throughput per service and method.",
                    "Server and client meters are separate families: match them by service and method tags before"
                            + " concluding where latency is introduced."),
            new MeterGroup(
                    "framework",
                    "Framework and runtime",
                    "Application framework and container instrumentation (Spring Boot, Quarkus, Hibernate, servlet"
                            + " containers, loggers, executors)",
                    "Framework-level instrumentation such as ORM statistics, servlet containers, executors,"
                            + " scheduled tasks, logging events and startup timings.",
                    "Most of these are cumulative counters or pool gauges scoped to one component, so read them per"
                            + " component tag and treat startup timers as one-off measurements."),
            new MeterGroup(
                    "mongodb",
                    "MongoDB",
                    "Micrometer MongoDB driver binders",
                    "Command timers and pool measurements already registered by the application's Mongo integration.",
                    "Commands are driver operations, not repository latency. Pools have their own connection semantics,"
                            + " not JDBC counters. Missing instrumentation is unavailable; BootUI registers no listener."),
            APPLICATION_GROUP);

    private static final List<MeterFamily> FAMILIES = List.of(
            family(
                    "mongodb.driver",
                    "MongoDB driver",
                    "mongodb",
                    List.of(
                            "mongodb.driver.commands",
                            "mongodb.driver.pool.size",
                            "mongodb.driver.pool.checkedout",
                            "mongodb.driver.pool.checkoutfailed",
                            "mongodb.driver.pool.waitqueuesize"),
                    List.of(),
                    "Native MongoDB command timing and pool size, checkout and waiting measurements.",
                    "Read the native meter type, tags and units. Command time is not total repository or session time;"
                            + " inspection commands may be included. No collection/index usage is inferred."),
            family(
                    "jvm.memory",
                    "JVM memory",
                    "jvm",
                    List.of(),
                    List.of("jvm.memory"),
                    "Heap and non-heap memory usage per memory pool.",
                    "Used, committed and maximum bytes are gauges per area and pool id: compare used against"
                            + " committed for one pool instead of summing pools."),
            family(
                    "jvm.gc",
                    "JVM garbage collection",
                    "jvm",
                    List.of(),
                    List.of("jvm.gc"),
                    "Garbage-collection pauses, concurrent phases and promoted or allocated bytes.",
                    "The pause timer's count is the number of collections since start, its total the accumulated"
                            + " pause time and its maximum the worst pause in the current window."),
            family(
                    "jvm.threads",
                    "JVM threads",
                    "jvm",
                    List.of(),
                    List.of("jvm.threads"),
                    "Live, daemon, peak and started thread counts, plus threads per state.",
                    "Live and peak counts are gauges, while the started count only grows: a rising live count with a"
                            + " flat peak usually means normal pool churn."),
            family(
                    "jvm.classes",
                    "JVM class loading",
                    "jvm",
                    List.of(),
                    List.of("jvm.classes"),
                    "Classes currently loaded and unloaded by the JVM.",
                    "The loaded gauge is instantaneous while the unloaded counter accumulates, so continuous growth"
                            + " in both hints at dynamic class generation."),
            family(
                    "jvm.buffer",
                    "JVM buffer pools",
                    "jvm",
                    List.of(),
                    List.of("jvm.buffer"),
                    "Direct and mapped byte-buffer pool usage outside the Java heap.",
                    "Buffer memory is not part of heap gauges, so track it separately when native memory grows while"
                            + " the heap looks healthy."),
            family(
                    "jvm.runtime",
                    "JVM runtime",
                    "jvm",
                    List.of("jvm.info"),
                    List.of("jvm.compilation"),
                    "JVM identity and just-in-time compilation time.",
                    "Compilation time accumulates for the process lifetime and is expected to flatten once the"
                            + " application warms up."),
            family(
                    "process.cpu",
                    "Process CPU",
                    "process",
                    List.of(),
                    List.of("process.cpu"),
                    "CPU usage and CPU time consumed by this JVM process.",
                    "Usage is a fraction of one host CPU-second sampled at read time, so short spikes can be missed"
                            + " by infrequent polling."),
            family(
                    "process.uptime",
                    "Process uptime",
                    "process",
                    List.of("process.uptime", "process.start.time"),
                    List.of(),
                    "How long this process has been running and when it started.",
                    "Uptime grows monotonically; a reset uptime next to a new start time means the process was"
                            + " restarted rather than the meter being reset."),
            family(
                    "process.files",
                    "Process file descriptors",
                    "process",
                    List.of(),
                    List.of("process.files"),
                    "Open and maximum file descriptors for this process.",
                    "Read the open gauge against the maximum gauge: a ratio that keeps climbing usually means"
                            + " descriptors are leaking rather than traffic growing."),
            family(
                    "system.cpu",
                    "System CPU",
                    "system",
                    List.of(),
                    List.of("system.cpu"),
                    "Host CPU utilisation and the number of CPUs visible to the JVM.",
                    "Utilisation is a host-wide fraction, so compare it with process CPU usage before attributing"
                            + " saturation to this application."),
            family(
                    "system.load",
                    "System load average",
                    "system",
                    List.of(),
                    List.of("system.load"),
                    "Operating-system load average over the last minute.",
                    "Load average counts runnable and uninterruptible tasks host-wide; divide it by the CPU count"
                            + " before comparing it with CPU utilisation."),
            family(
                    "system.disk",
                    "Disk space",
                    "system",
                    List.of("disk.free", "disk.total"),
                    List.of(),
                    "Free and total disk space for the paths the runtime monitors.",
                    "Both are gauges in bytes for one path tag: derive used space by subtracting free from total for"
                            + " the same path."),
            family(
                    "http.server.requests",
                    "HTTP server requests",
                    "http-server",
                    List.of(),
                    List.of("http.server.requests", "http.server.active.requests"),
                    "Timer over inbound HTTP requests, tagged with method, URI template, status and outcome.",
                    "Count is requests since start, total is accumulated latency and maximum is the slowest request"
                            + " in the current window; compare per URI template rather than in aggregate."),
            family(
                    "http.server.transport",
                    "HTTP server transport",
                    "http-server",
                    List.of(),
                    List.of("http.server.bytes", "http.server.connections"),
                    "Connection and byte activity below the request layer.",
                    "These describe transport work rather than handler latency, so a growing byte counter with"
                            + " steady request timings simply means larger payloads."),
            family(
                    "http.client.requests",
                    "HTTP client requests",
                    "http-client",
                    List.of(),
                    List.of("http.client.requests"),
                    "Timer over outbound HTTP calls, tagged with method, target URI template, status and outcome.",
                    "A rising count with flat total time points at cheap repeated calls, while a rising maximum with"
                            + " a steady count points at a slow dependency."),
            family(
                    "http.client.transport",
                    "HTTP client transport",
                    "http-client",
                    List.of(),
                    List.of("http.client.bytes", "http.client.connections", "httpcomponents.httpclient", "okhttp"),
                    "Connection-pool and transport activity for HTTP client libraries.",
                    "Pool gauges are instantaneous: sustained pending connections indicate an undersized client pool"
                            + " rather than a slow server."),
            family(
                    "datasource.hikaricp",
                    "HikariCP pool",
                    "datasource",
                    List.of(),
                    List.of("hikaricp"),
                    "HikariCP pool size, usage, acquisition timing, creation timing and timeouts.",
                    "Active, idle and pending are gauges for one pool tag, while the timeout counter accumulates:"
                            + " pending above zero with a maxed active gauge means the pool is exhausted."),
            family(
                    "datasource.agroal",
                    "Agroal pool",
                    "datasource",
                    List.of(),
                    List.of("agroal"),
                    "Agroal pool counts, acquisition timing and awaiting requests on Quarkus datasources.",
                    "Available and active counts are gauges per datasource; a rising awaiting count is the pool"
                            + " exhaustion signal."),
            family(
                    "datasource.jdbc",
                    "JDBC connections",
                    "datasource",
                    List.of(),
                    List.of("jdbc.connections"),
                    "Micrometer's generic JDBC connection-pool gauges.",
                    "These are pool-agnostic gauges: read active against maximum for one datasource tag rather than"
                            + " summing datasources."),
            family(
                    "cache.binder",
                    "Cache statistics",
                    "cache",
                    List.of(
                            "cache.size",
                            "cache.gets",
                            "cache.puts",
                            "cache.hits",
                            "cache.misses",
                            "cache.evictions",
                            "cache.eviction.weight",
                            "cache.removals",
                            "cache.loads",
                            "cache.load.duration",
                            "cache.load.failure",
                            "cache.load.success",
                            "cache.puts.added"),
                    List.of(),
                    "Provider statistics republished by Micrometer's cache binders.",
                    "Counters are cumulative for the cache's lifetime and tagged by cache name and provider, so"
                            + " derive hit ratios from hits and misses read together over the same interval."),
            family(
                    "messaging.kafka",
                    "Kafka client",
                    "messaging",
                    List.of(),
                    List.of("kafka.consumer", "kafka.producer", "kafka.streams", "kafka.admin.client", "spring.kafka"),
                    "Kafka producer, consumer and listener throughput, lag and timing.",
                    "The Kafka client mixes cumulative totals with client-computed rate gauges, so check the base"
                            + " unit and prefer the total when comparing intervals."),
            family(
                    "messaging.rabbitmq",
                    "RabbitMQ client",
                    "messaging",
                    List.of(),
                    List.of("rabbitmq", "spring.rabbitmq"),
                    "RabbitMQ connection, channel and message activity.",
                    "Connection and channel gauges are instantaneous while published and consumed counters"
                            + " accumulate, so read them together to separate churn from throughput."),
            family(
                    "messaging.jms",
                    "JMS messaging",
                    "messaging",
                    List.of(),
                    List.of("jms.message", "jms.session"),
                    "JMS publish and process timings recorded by Micrometer's JMS instrumentation.",
                    "Publish and process are separate timers: compare their counts to see whether a backlog is"
                            + " produced faster than it is consumed."),
            family(
                    "resilience.resilience4j",
                    "Resilience4j",
                    "resilience",
                    List.of(),
                    List.of("resilience4j"),
                    "Resilience4j circuit-breaker, retry, bulkhead, rate-limiter and time-limiter meters.",
                    "Calls are tagged by kind and outcome and states are gauges, so read a state gauge of one"
                            + " together with the failed-call counter before declaring an outage."),
            family(
                    "resilience.smallrye",
                    "SmallRye Fault Tolerance",
                    "resilience",
                    List.of(),
                    List.of(
                            "ft.invocations",
                            "ft.retry",
                            "ft.timeout",
                            "ft.circuitbreaker",
                            "ft.bulkhead",
                            "ft.ratelimit"),
                    "MicroProfile Fault Tolerance invocation, retry, timeout, circuit-breaker and bulkhead counters.",
                    "Invocations are tagged by result and fallback: a high retried count with a stable succeeded"
                            + " count means recovery is working but the dependency is unstable."),
            family(
                    "grpc.server",
                    "gRPC server",
                    "grpc",
                    List.of(),
                    List.of("grpc.server"),
                    "Inbound gRPC calls handled by this application.",
                    "Call counters are tagged by service, method and status code; read them per method rather than"
                            + " per service."),
            family(
                    "grpc.client",
                    "gRPC client",
                    "grpc",
                    List.of(),
                    List.of("grpc.client"),
                    "Outbound gRPC calls this application makes.",
                    "Compare client latency with the server family of the callee before attributing latency to the"
                            + " network."),
            family(
                    "framework.hibernate",
                    "Hibernate ORM",
                    "framework",
                    List.of(),
                    List.of("hibernate"),
                    "Hibernate session, query, cache and connection statistics.",
                    "These counters are cumulative per session factory and only populated when Hibernate statistics"
                            + " are enabled, so zero values can mean statistics are off rather than idle."),
            family(
                    "framework.servlet.container",
                    "Servlet container",
                    "framework",
                    List.of(),
                    List.of("tomcat", "jetty", "undertow"),
                    "Embedded servlet container sessions, threads and connection handling.",
                    "Thread and session gauges are instantaneous per pool or context; compare busy threads with the"
                            + " configured maximum before scaling the container."),
            family(
                    "framework.logging",
                    "Logging events",
                    "framework",
                    List.of(),
                    List.of("logback.events", "log4j2.events"),
                    "Log events counted per level.",
                    "The counter accumulates for the process lifetime, so a jump in the error level relative to its"
                            + " own earlier value is the signal rather than the absolute total."),
            family(
                    "framework.executor",
                    "Executors",
                    "framework",
                    List.of(
                            "executor",
                            "executor.active",
                            "executor.completed",
                            "executor.idle",
                            "executor.execution",
                            "executor.queued",
                            "executor.queue.remaining",
                            "executor.pool.size",
                            "executor.pool.core",
                            "executor.pool.max",
                            "executor.scheduled.once",
                            "executor.scheduled.repetitively"),
                    List.of("tasks.scheduled.execution"),
                    "Thread-pool executor queues, active threads, completed tasks and scheduled-task timings.",
                    "Queue and active gauges are instantaneous per executor name; a growing queued gauge with a"
                            + " saturated active gauge means the pool is the bottleneck."),
            family(
                    "framework.spring.data",
                    "Spring Data repositories",
                    "framework",
                    List.of(),
                    List.of("spring.data.repository"),
                    "Timings for Spring Data repository method invocations.",
                    "Timers are tagged by repository, method and outcome, so compare methods of one repository"
                            + " rather than repositories against each other."),
            family(
                    "framework.spring.security",
                    "Spring Security",
                    "framework",
                    List.of(),
                    List.of("spring.security"),
                    "Authentication, authorization and filter-chain timings from Spring Security.",
                    "Counters are tagged by outcome and authentication type: a rising failure count with a steady"
                            + " total usually means credential problems rather than load."),
            family(
                    "framework.startup",
                    "Application startup",
                    "framework",
                    List.of("application.started.time", "application.ready.time"),
                    List.of(),
                    "One-off timers recording when the application context started and became ready.",
                    "These are recorded once per boot, so treat them as a startup budget rather than a live"
                            + " series."),
            family(
                    "framework.vertx",
                    "Vert.x runtime",
                    "framework",
                    List.of(),
                    List.of("vertx", "worker.pool"),
                    "Vert.x event-loop and worker-pool activity, as used by Quarkus and Vert.x applications.",
                    "Event-loop and pool gauges are instantaneous; sustained queue delay is the signal that blocking"
                            + " work is running on an event loop."),
            family(
                    "framework.netty",
                    "Netty allocators and event loops",
                    "framework",
                    List.of(),
                    List.of("netty"),
                    "Netty buffer allocator and event-loop activity below the HTTP layer.",
                    "Allocator gauges measure pooled native and heap memory rather than the JVM heap, so read them"
                            + " next to the JVM buffer-pool family."),
            family(
                    "framework.reactor.netty",
                    "Reactor Netty",
                    "framework",
                    List.of(),
                    List.of("reactor.netty"),
                    "Reactor Netty connection-provider and server activity underneath Spring WebFlux.",
                    "Pending connections and idle gauges are instantaneous per connection provider; a sustained"
                            + " pending count points at an undersized pool rather than a slow remote."));

    private static final Map<String, Integer> GROUP_ORDER = groupOrder();

    private MeterFamilyCatalogue() {}

    /** Every provenance group, in display order, ending with the application/unclassified group. */
    public static List<MeterGroup> groups() {
        return GROUPS;
    }

    /** Every curated family, in catalogue order. */
    public static List<MeterFamily> families() {
        return FAMILIES;
    }

    /** The application/unclassified group used when no family matches a meter name. */
    public static MeterGroup applicationGroup() {
        return APPLICATION_GROUP;
    }

    /** Groups indexed by id, preserving display order. */
    public static Map<String, MeterGroup> groupsById() {
        Map<String, MeterGroup> byId = new LinkedHashMap<>();
        for (MeterGroup group : GROUPS) {
            byId.put(group.id(), group);
        }
        return byId;
    }

    /** The group a family belongs to, or the application group when the family declares an unknown group. */
    public static MeterGroup groupOf(MeterFamily family) {
        if (family == null) {
            return APPLICATION_GROUP;
        }
        for (MeterGroup group : GROUPS) {
            if (group.id().equals(family.groupId())) {
                return group;
            }
        }
        return APPLICATION_GROUP;
    }

    /** Display order index of a group id; unknown ids sort last. */
    public static int groupOrder(String groupId) {
        Integer order = GROUP_ORDER.get(groupId);
        return order == null ? GROUPS.size() : order;
    }

    private static Map<String, Integer> groupOrder() {
        Map<String, Integer> order = new LinkedHashMap<>();
        for (int index = 0; index < GROUPS.size(); index++) {
            order.put(GROUPS.get(index).id(), index);
        }
        return Map.copyOf(order);
    }

    private static MeterFamily family(
            String id,
            String label,
            String groupId,
            List<String> exactNames,
            List<String> prefixes,
            String summary,
            String interpretation) {
        return new MeterFamily(id, label, groupId, exactNames, prefixes, summary, interpretation);
    }
}
