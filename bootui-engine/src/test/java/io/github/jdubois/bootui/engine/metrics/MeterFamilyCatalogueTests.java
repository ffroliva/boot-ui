package io.github.jdubois.bootui.engine.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.MetricProvenanceDto;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Contract tests for the curated meter catalogue and its classifier.
 *
 * <p>These pin the two properties the feature depends on: well-known integration families are recognized from
 * their documented naming conventions, and application meters with similar-looking prefixes are <em>not</em>
 * silently absorbed into a curated family.</p>
 */
class MeterFamilyCatalogueTests {

    private final MeterProvenanceClassifier classifier = new MeterProvenanceClassifier();

    @Test
    void catalogueIsVersionedAndInternallyConsistent() {
        assertThat(MeterFamilyCatalogue.VERSION).isNotBlank();

        Set<String> groupIds = MeterFamilyCatalogue.groupsById().keySet();
        assertThat(groupIds).contains(MeterFamilyCatalogue.APPLICATION_GROUP_ID);

        Set<String> familyIds = new HashSet<>();
        Set<String> patterns = new HashSet<>();
        for (MeterFamily family : MeterFamilyCatalogue.families()) {
            assertThat(familyIds.add(family.id()))
                    .as("family id %s is unique", family.id())
                    .isTrue();
            assertThat(groupIds)
                    .as("family %s declares a known group", family.id())
                    .contains(family.groupId());
            assertThat(family.groupId())
                    .as("family %s is not filed under the unclassified group", family.id())
                    .isNotEqualTo(MeterFamilyCatalogue.APPLICATION_GROUP_ID);
            assertThat(family.summary())
                    .as("family %s has a summary", family.id())
                    .isNotBlank();
            assertThat(family.exactNames().size() + family.prefixes().size())
                    .as("family %s declares at least one pattern", family.id())
                    .isPositive();

            List<String> declared = new ArrayList<>(family.exactNames());
            declared.addAll(family.prefixes());
            for (String pattern : declared) {
                assertThat(patterns.add(pattern))
                        .as("pattern %s is owned by exactly one family", pattern)
                        .isTrue();
            }
        }
    }

    @Test
    void everyGroupDocumentsItsContributorAndInterpretation() {
        for (MeterGroup group : MeterFamilyCatalogue.groups()) {
            assertThat(group.label()).as("group %s label", group.id()).isNotBlank();
            assertThat(group.contributor())
                    .as("group %s contributor", group.id())
                    .isNotBlank();
            assertThat(group.summary()).as("group %s summary", group.id()).isNotBlank();
            assertThat(group.interpretation())
                    .as("group %s interpretation", group.id())
                    .isNotBlank();
        }
        assertThat(MeterFamilyCatalogue.groups())
                .last()
                .extracting(MeterGroup::id)
                .as("the unclassified group is always rendered last")
                .isEqualTo(MeterFamilyCatalogue.APPLICATION_GROUP_ID);
    }

    @ParameterizedTest
    @CsvSource({
        "jvm.memory.used,jvm.memory,jvm",
        "mongodb.driver.commands,mongodb.driver,mongodb",
        "mongodb.driver.pool.size,mongodb.driver,mongodb",
        "mongodb.driver.pool.checkedout,mongodb.driver,mongodb",
        "mongodb.driver.pool.checkoutfailed,mongodb.driver,mongodb",
        "mongodb.driver.pool.waitqueuesize,mongodb.driver,mongodb",
        "jvm.gc.pause,jvm.gc,jvm",
        "jvm.threads.live.threads,jvm.threads,jvm",
        "jvm.classes.loaded,jvm.classes,jvm",
        "process.uptime,process.uptime,process",
        "process.cpu.usage,process.cpu,process",
        "system.cpu.count,system.cpu,system",
        "disk.free,system.disk,system",
        "http.server.requests,http.server.requests,http-server",
        "http.server.bytes.read,http.server.transport,http-server",
        "http.client.requests,http.client.requests,http-client",
        "hikaricp.connections.active,datasource.hikaricp,datasource",
        "agroal.active.count,datasource.agroal,datasource",
        "jdbc.connections.max,datasource.jdbc,datasource",
        "cache.gets,cache.binder,cache",
        "kafka.consumer.fetch.manager.records.lag,messaging.kafka,messaging",
        "rabbitmq.published,messaging.rabbitmq,messaging",
        "jms.message.publish,messaging.jms,messaging",
        "resilience4j.circuitbreaker.calls,resilience.resilience4j,resilience",
        "ft.retry.calls.total,resilience.smallrye,resilience",
        "grpc.server.calls.received,grpc.server,grpc",
        "grpc.client.processing.duration,grpc.client,grpc",
        "hibernate.sessions.open,framework.hibernate,framework",
        "tomcat.sessions.active.current,framework.servlet.container,framework",
        "logback.events,framework.logging,framework",
        "executor.queued,framework.executor,framework",
        "application.ready.time,framework.startup,framework",
        "worker.pool.queue.size,framework.vertx,framework",
        "vertx.http.client.active.requests,framework.vertx,framework",
        "netty.eventexecutor.tasks.pending,framework.netty,framework",
        "reactor.netty.connection.provider.pending.connections,framework.reactor.netty,framework",
        "okhttp.requests,http.client.transport,http-client",
        "kafka.producer.record.send.total,messaging.kafka,messaging"
    })
    void classifiesWellKnownIntegrationFamilies(String meterName, String expectedFamily, String expectedGroup) {
        MeterFamily family = classifier.classify(meterName);

        assertThat(family).as("family for %s", meterName).isNotNull();
        assertThat(family.id()).isEqualTo(expectedFamily);
        assertThat(MeterFamilyCatalogue.groupOf(family).id()).isEqualTo(expectedGroup);
    }

    /**
     * Records the deliberate limit of name-based classification: inside a namespace a binder owns outright, an
     * application meter that borrows the namespace is attributed to that binder's family. The catalogue narrows the
     * prefixes an application is plausibly likely to reuse ({@code http.server}, {@code http.client}, {@code kafka},
     * {@code executor}, {@code cache}) and accepts absorption for the rest rather than claiming false precision.
     */
    @ParameterizedTest
    @CsvSource({
        "tomcat.orders.active,framework.servlet.container",
        "hibernate.orders.count,framework.hibernate",
        "jvm.memory.orders.used,jvm.memory"
    })
    void absorbsApplicationMetersThatBorrowABinderOwnedNamespace(String meterName, String expectedFamily) {
        MeterFamily family = classifier.classify(meterName);

        assertThat(family).as("family for %s", meterName).isNotNull();
        assertThat(family.id()).isEqualTo(expectedFamily);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jvmx.memory.used",
                "mongodb.application.orders",
                "mongodb.driver.commands.custom",
                "jvm_memory_used",
                "jvmmemory.used",
                "cache.orders.entries",
                "caches.gets",
                "httpserver.requests",
                "http.serverless.requests",
                "process.orders.uptime",
                "application.orders.processed",
                "diskspace.free",
                "ft.orders.processed",
                "executorservice.tasks",
                "orders.processed",
                "http.server.orders",
                "http.client.orders.duration",
                "kafka.orders.lag",
                "executor.orders.queue",
                "reactor.orders.processed",
                "nettyx.buffers"
            })
    void leavesLookalikeApplicationMetersUnclassified(String meterName) {
        assertThat(classifier.classify(meterName))
                .as("family for %s", meterName)
                .isNull();

        MetricProvenanceDto provenance = classifier.provenance(meterName, null);
        assertThat(provenance.classified()).isFalse();
        assertThat(provenance.groupId()).isEqualTo(MeterFamilyCatalogue.APPLICATION_GROUP_ID);
        assertThat(provenance.familyId()).isNull();
        assertThat(provenance.explanation()).isNull();
        assertThat(provenance.explanationSource()).isEqualTo(MeterProvenanceClassifier.SOURCE_UNKNOWN);
        assertThat(provenance.interpretation()).isNull();
    }

    @Test
    void longerPatternsWinSoNestedNamespacesAreNotStolen() {
        assertThat(classifier.classify("http.server.requests.active").id()).isEqualTo("http.server.requests");
        assertThat(classifier.classify("http.server.connections").id()).isEqualTo("http.server.transport");
    }

    @Test
    void matchingIsCaseInsensitiveAndRepeatable() {
        MeterFamily first = classifier.classify("JVM.Memory.Used");
        MeterFamily second = classifier.classify("jvm.memory.used");

        assertThat(first).isNotNull();
        assertThat(first.id()).isEqualTo("jvm.memory");
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(classifier.classify("JVM.Memory.Used").id()).isEqualTo("jvm.memory");
    }

    @Test
    void nativeDescriptionsWinOverCuratedExplanations() {
        MetricProvenanceDto nativeProvenance = classifier.provenance("jvm.memory.used", "  Memory used  ");
        assertThat(nativeProvenance.explanation()).isEqualTo("Memory used");
        assertThat(nativeProvenance.explanationSource()).isEqualTo(MeterProvenanceClassifier.SOURCE_NATIVE);
        assertThat(nativeProvenance.classified()).isTrue();
        assertThat(nativeProvenance.contributor()).isEqualTo("Micrometer JVM binders");
        assertThat(nativeProvenance.interpretation()).isNotBlank();

        MetricProvenanceDto curated = classifier.provenance("jvm.memory.used", "   ");
        assertThat(curated.explanationSource()).isEqualTo(MeterProvenanceClassifier.SOURCE_CURATED);
        assertThat(curated.explanation()).isEqualTo("Heap and non-heap memory usage per memory pool.");
    }

    @Test
    void unclassifiedMetersKeepTheirNativeDescription() {
        MetricProvenanceDto provenance = classifier.provenance("orders.processed", "Orders processed");

        assertThat(provenance.classified()).isFalse();
        assertThat(provenance.groupLabel()).isEqualTo("Application / unclassified");
        assertThat(provenance.explanation()).isEqualTo("Orders processed");
        assertThat(provenance.explanationSource()).isEqualTo(MeterProvenanceClassifier.SOURCE_NATIVE);
        assertThat(provenance.interpretation()).isNull();
    }

    @Test
    void blankAndOverlongNamesAreUnclassifiedRatherThanGuessed() {
        assertThat(classifier.classify(null)).isNull();
        assertThat(classifier.classify("   ")).isNull();
        assertThat(classifier.classify("jvm.memory." + "x".repeat(600))).isNull();
    }

    @Test
    void classificationCacheStaysBounded() {
        for (int index = 0; index < MeterProvenanceClassifier.MAX_CACHED_NAMES + 500; index++) {
            classifier.classify("orders.processed." + index);
        }

        assertThat(classifier.cacheSize())
                .as("the memo cache stops growing at its bound")
                .isLessThanOrEqualTo(MeterProvenanceClassifier.MAX_CACHED_NAMES);
        assertThat(classifier.classify("jvm.memory.used").id())
                .as("classification stays correct once the cache is saturated")
                .isEqualTo("jvm.memory");
    }
}
