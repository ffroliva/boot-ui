package io.github.jdubois.bootui.autoconfigure.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.github.jdubois.bootui.autoconfigure.web.DataController;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.DocumentReference;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.support.RepositoryFactoryInformation;
import tools.jackson.databind.json.JsonMapper;

class SpringDataMongoMetadataTests {
    static final AtomicInteger EVALUATIONS = new AtomicInteger();

    public static String collectionName() {
        EVALUATIONS.incrementAndGet();
        return "unsafe";
    }

    @Test
    void extractsStaticDeclarationsAndNeverEvaluatesDynamicCollectionOrQuery() throws Exception {
        for (ValueExposure mode : ValueExposure.values()) {
            for (boolean masking : new boolean[] {true, false}) {
                var provider = new SpringDataMongoMetadataProvider(policy(mode, masking));
                var metadata = provider.describe(Order.class);
                assertThat(metadata.mapping().collectionState()).isEqualTo("STATIC");
                assertThat(metadata.mapping().collection())
                        .isEqualTo(mode == ValueExposure.METADATA_ONLY ? "******" : "orders");
                assertThat(provider.summary(Order.class).mapping().collection())
                        .isEqualTo(metadata.mapping().collection());
                assertThat(metadata.declaredIndexes()).hasSize(2);
                assertThat(metadata.binding()).isEqualTo("UNRESOLVED");
                var dynamic = provider.describe(DynamicOrder.class);
                assertThat(dynamic.mapping().collectionState()).isEqualTo("DYNAMIC");
                assertThat(dynamic.mapping().collection()).isNull();
                var query = provider.query(Orders.class.getMethod("bySecret", String.class), "ANNOTATED");
                var aggregation = provider.query(Orders.class.getMethod("aggregateSecret"), "QUERY");
                assertThat(query.kind()).isEqualTo("MONGO_QUERY");
                assertThat(query.dynamic()).isTrue();
                assertThat(aggregation.kind()).isEqualTo("MONGO_AGGREGATION");
                assertThat(aggregation.pipelineStages()).isEqualTo(1);
                String json = new JsonMapper().writeValueAsString(List.of(metadata, dynamic, query, aggregation));
                assertThat(json).doesNotContain("SECRET_SENTINEL", "collectionName", "#{", "$match");
                assertThat(EVALUATIONS).hasValue(0);
            }
        }
    }

    @Test
    void dataControllerWithholdsLegacyMongoQueryAndKeepsReactiveClassification() throws Exception {
        for (Class<?> repository : List.of(Orders.class, ReactiveOrders.class)) {
            DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
            RepositoryFactoryInformation<?, ?> factory = mock(RepositoryFactoryInformation.class);
            RepositoryInformation information = mock(RepositoryInformation.class);
            when(factory.getRepositoryInformation()).thenReturn(information);
            doReturn(repository).when(information).getRepositoryInterface();
            doReturn(Order.class).when(information).getDomainType();
            doReturn(String.class).when(information).getIdType();
            when(information.isQueryMethod(any())).thenReturn(true);
            beans.registerSingleton("orders", factory);
            beans.registerSingleton("factory", beans);
            DataController controller = new DataController(beans.getBeanProvider(ListableBeanFactory.class));
            var summary = controller.repositories().repositories().get(0);
            assertThat(summary.storeModule()).isEqualTo("MONGO");
            assertThat(summary.executionKind()).isEqualTo(repository == Orders.class ? "IMPERATIVE" : "REACTIVE");
            var detail = controller.repository("orders").getBody();
            assertThat(detail.methods()).allMatch(method -> method.query() == null && method.namedQuery() == null);
            assertThat(new JsonMapper().writeValueAsString(detail)).doesNotContain("SECRET_SENTINEL");
        }
    }

    @Test
    void nonMongoQueryExposureRemainsUnchanged() throws Exception {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        RepositoryFactoryInformation<?, ?> factory = mock(RepositoryFactoryInformation.class);
        RepositoryInformation info = mock(RepositoryInformation.class);
        when(factory.getRepositoryInformation()).thenReturn(info);
        doReturn(JpaOrders.class).when(info).getRepositoryInterface();
        doReturn(Order.class).when(info).getDomainType();
        doReturn(String.class).when(info).getIdType();
        when(info.isQueryMethod(any())).thenReturn(true);
        beans.registerSingleton("orders", factory);
        beans.registerSingleton("factory", beans);
        var controller = new DataController(beans.getBeanProvider(ListableBeanFactory.class));
        var detail = controller.repository("orders").getBody();
        assertThat(detail.methods())
                .filteredOn(method -> method.name().equals("declared"))
                .singleElement()
                .satisfies(method -> {
                    assertThat(method.query()).isEqualTo("select o from Order o");
                    assertThat(method.origin()).isEqualTo("ANNOTATED");
                });
    }

    private static ExposurePolicy policy(ValueExposure mode, boolean masking) {
        return new ExposurePolicy() {
            public ValueExposure valueExposure() {
                return mode;
            }

            public boolean maskSecrets() {
                return masking;
            }
        };
    }

    @Document("orders")
    @CompoundIndex(
            name = "tenant_created",
            def = "{'tenant':1,'created':-1}",
            partialFilter = "{'secret':'SECRET_SENTINEL'}")
    static class Order {
        @Id
        String id;

        @Version
        long version;

        @Field("tenant")
        @Indexed(unique = true, expireAfter = "60s")
        String account;

        String created;

        @DocumentReference(lookup = "{'secret':'SECRET_SENTINEL'}")
        Order parent;
    }

    @Document("#{T(io.github.jdubois.bootui.autoconfigure.mongodb.SpringDataMongoMetadataTests).collectionName()}")
    static class DynamicOrder {
        String id;
    }

    interface Orders extends MongoRepository<Order, String> {
        @Query(value = "{'secret':'SECRET_SENTINEL','tenant':?#{[0]}}", fields = "{'SECRET_SENTINEL':1}")
        Order bySecret(String tenant);

        @Aggregation(pipeline = {"{$match:{secret:'SECRET_SENTINEL'}}"})
        List<Order> aggregateSecret();
    }

    interface ReactiveOrders extends ReactiveMongoRepository<Order, String> {}

    interface JpaOrders extends org.springframework.data.jpa.repository.JpaRepository<Order, String> {
        @org.springframework.data.jpa.repository.Query("select o from Order o")
        Order declared();
    }
}
