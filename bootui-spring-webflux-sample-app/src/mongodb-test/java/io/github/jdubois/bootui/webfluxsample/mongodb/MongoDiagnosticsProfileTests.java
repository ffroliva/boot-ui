package io.github.jdubois.bootui.webfluxsample.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.ClassUtils;
import reactor.core.publisher.Flux;

class MongoDiagnosticsProfileTests {

    @Test
    void profileUsesReactiveOnlyMongoWithExplicitExternalFixtureAndExistingH2() {
        assertThat(ClassUtils.isPresent(
                        "com.mongodb.reactivestreams.client.MongoClient",
                        getClass().getClassLoader()))
                .isTrue();
        assertThat(ClassUtils.isPresent(
                        "com.mongodb.client.MongoClient", getClass().getClassLoader()))
                .isFalse();
        assertThat(ClassUtils.isPresent(
                        "jakarta.persistence.EntityManager", getClass().getClassLoader()))
                .isFalse();
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "spring.profiles.active=mongodb-diagnostics",
                        "BOOTUI_SAMPLE_MONGODB_URL=mongodb://fixture:fixture@127.0.0.1:49157/bootui_sample")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var environment = context.getEnvironment();
                    assertThat(environment.getProperty("spring.mongodb.uri")).contains("127.0.0.1:49157");
                    assertThat(environment.getProperty("spring.mongodb.database"))
                            .isEqualTo("bootui_sample");
                    assertThat(environment.getProperty("spring.data.mongodb.auto-index-creation"))
                            .isEqualTo("false");
                    assertThat(environment.getProperty("spring.datasource.url")).startsWith("jdbc:h2:");
                });
    }

    @Test
    void workloadComposesReactiveOperationsWithoutAnEagerSubscription() {
        var repository = mock(SampleReactiveMongoRepository.class);
        when(repository.saveAll(ArgumentMatchers.<Iterable<SampleReactiveMongoProduct>>any()))
                .thenReturn(Flux.empty());
        when(repository.findTop3ByCategoryOrderBySkuAsc("reactive"))
                .thenReturn(Flux.fromIterable(List.of(
                        new SampleReactiveMongoProduct("webflux-one", "WEBFLUX-ONE", "reactive", true),
                        new SampleReactiveMongoProduct("webflux-two", "WEBFLUX-TWO", "reactive", true))));
        var result = new SampleReactiveMongoController(repository).workload();
        org.mockito.Mockito.verifyNoInteractions(repository);
        var summary = result.block(Duration.ofSeconds(1));
        assertThat(summary).isNotNull();
        assertThat(summary.upserted()).isEqualTo(2);
        assertThat(summary.productsRead()).isEqualTo(2);
    }
}
