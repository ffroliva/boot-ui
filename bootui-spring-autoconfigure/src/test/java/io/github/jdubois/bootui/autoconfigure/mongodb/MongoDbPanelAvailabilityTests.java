package io.github.jdubois.bootui.autoconfigure.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.web.PanelsController;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class MongoDbPanelAvailabilityTests {
    private static final AtomicInteger CREATIONS = new AtomicInteger();

    @Test
    void manifestInspectsDeclarationsWithoutInitializingAnyFactoryBean() {
        CREATIONS.set(0);
        try (var context = new GenericApplicationContext()) {
            var definition = new RootBeanDefinition(LazyClientFactory.class);
            definition.setLazyInit(true);
            context.registerBeanDefinition("mongoClient", definition);
            context.refresh();
            var report = new PanelsController(context, new MockEnvironment(), new BootUiProperties()).panels();
            assertThat(report.panels())
                    .filteredOn(panel -> panel.id().equals("mongodb"))
                    .singleElement()
                    .satisfies(panel -> assertThat(panel.available()).isTrue());
            assertThat(CREATIONS).hasValue(0);
        }
    }

    public static class LazyClientFactory implements FactoryBean<MongoClient> {
        public LazyClientFactory() {
            CREATIONS.incrementAndGet();
        }

        public MongoClient getObject() {
            throw new AssertionError("Mongo client created by manifest");
        }

        public Class<?> getObjectType() {
            return MongoClient.class;
        }
    }

    @Test
    void resolvingTheLocalRecorderStillDoesNotInstantiateUnrelatedMongoFactories() {
        CREATIONS.set(0);
        AtomicInteger recorders = new AtomicInteger();
        try (var context = new GenericApplicationContext()) {
            var mongo = new RootBeanDefinition(LazyClientFactory.class);
            mongo.setLazyInit(true);
            context.registerBeanDefinition("mongoClient", mongo);
            var recorder = new RootBeanDefinition(
                    io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder.class, () -> {
                        recorders.incrementAndGet();
                        return org.mockito.Mockito.mock(
                                io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder.class);
                    });
            recorder.setLazyInit(true);
            context.registerBeanDefinition("bootUiRestClientTraceRecorder", recorder);
            context.refresh();
            new PanelsController(context, new MockEnvironment(), new BootUiProperties()).panels();
            assertThat(recorders).hasValue(1);
            assertThat(CREATIONS).hasValue(0);
        }
    }
}
