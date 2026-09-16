package io.github.jdubois.bootui.autoconfigure.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiAutoConfiguration;
import io.github.jdubois.bootui.autoconfigure.BootUiReactiveAutoConfiguration;
import io.github.jdubois.bootui.engine.mongodb.MongoDbInspectionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class MongoDbAbsenceTests {
    @Test
    void mvcAndWebFluxStartWithoutEitherDriverOrSpringDataMongo() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader("com.mongodb", "org.bson", "org.springframework.data.mongodb"))
                .withPropertyValues("bootui.enabled=ON")
                .withInitializer(context -> addUnrelatedProperties(context.getEnvironment()))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(MongoDbInspectionService.class);
                    assertThat(context.getBean(MongoDbController.class).report().available())
                            .isFalse();
                });
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BootUiReactiveAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader(
                        "com.mongodb",
                        "org.bson",
                        "org.springframework.data.mongodb",
                        "jakarta.servlet",
                        "org.springframework.data.jpa"))
                .withPropertyValues("bootui.enabled=ON")
                .withInitializer(context -> addUnrelatedProperties(context.getEnvironment()))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(MongoDbInspectionService.class);
                    assertThat(context.getBean(MongoDbController.class).report().available())
                            .isFalse();
                });
    }

    @Test
    void eachStyleLinksWithoutItsCounterpart() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class))
                .withClassLoader(
                        new FilteredClassLoader("com.mongodb.reactivestreams", "org.springframework.data.mongodb"))
                .withPropertyValues("bootui.enabled=ON")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(MongoDbClientDeclarations.Reader.class)
                                    .values())
                            .extracting(MongoDbClientDeclarations.Reader::style)
                            .containsExactly("SYNC");
                });
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BootUiReactiveAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader(
                        (java.util.function.Predicate<String>) name -> name.equals("com.mongodb.client.MongoClient")
                                || name.equals("com.mongodb.client.MongoCluster")
                                || name.startsWith("com.mongodb.client.internal.")
                                || name.startsWith("org.springframework.data.mongodb.")))
                .withPropertyValues("bootui.enabled=ON")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(MongoDbClientDeclarations.Reader.class)
                                    .values())
                            .extracting(MongoDbClientDeclarations.Reader::style)
                            .containsExactly("REACTIVE");
                });
    }

    @Test
    void inactiveBootUiNeverValidatesOrWiresMongoIntegration() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class))
                .withPropertyValues("bootui.enabled=OFF", "bootui.mongodb.timeout-ms=invalid")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(MongoDbInspectionService.class));
    }

    @Test
    void invalidScopeFailsStartupWithTheIntegrationRatherThanBecomingAnEmptyReport() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class))
                .withPropertyValues("bootui.enabled=ON", "bootui.mongodb.clients.client.databases=one,one")
                .run(context -> assertThat(context).hasFailed());
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BootUiReactiveAutoConfiguration.class))
                .withPropertyValues("bootui.enabled=ON", "bootui.mongodb.clients.client.databases=one,one")
                .run(context -> assertThat(context).hasFailed());
    }

    private static void addUnrelatedProperties(org.springframework.core.env.ConfigurableEnvironment environment) {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 4097; i++) values.put("unrelated.setting." + i, "ordinary");
        environment
                .getPropertySources()
                .addFirst(new org.springframework.core.env.MapPropertySource("unrelated", values));
    }
}
