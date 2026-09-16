package io.github.jdubois.bootui.autoconfigure.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.MongoDbReportContract;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

/** Real positive REST/MCP/CLI matrix; not an unavailable-only conformance run. */
class MongoDbHttpLiveTests {
    private static MongoDbLiveFixture fixture;
    private static final java.util.concurrent.atomic.AtomicInteger LAZY_FACTORIES =
            new java.util.concurrent.atomic.AtomicInteger();

    @BeforeAll
    static void start() {
        fixture = new MongoDbLiveFixture();
    }

    @AfterAll
    static void stop() {
        if (fixture != null) fixture.close();
    }

    @ParameterizedTest
    @CsvSource({"SERVLET,false", "SERVLET,true", "REACTIVE,false", "REACTIVE,true"})
    void everyTransportUsesExistingSyncAndReactiveClients(WebApplicationType type, boolean custom) {
        try (var builtUi = new BuiltUiResources();
                var sync = fixture.sync("application", 4000);
                var reactive = fixture.reactive("application", 4000)) {
            SpringApplication application = new SpringApplication(HttpConfiguration.class);
            application.setWebApplicationType(type);
            application.setRegisterShutdownHook(false);
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("server.port", "0");
            settings.put("spring.config.location", "optional:classpath:/mongodb-http-fixture.properties");
            settings.put("spring.main.banner-mode", "off");
            settings.put("spring.flyway.enabled", "false");
            settings.put("spring.liquibase.enabled", "false");
            settings.put("spring.jpa.hibernate.ddl-auto", "none");
            settings.put("bootui.enabled", "ON");
            settings.put("bootui.show-banner", "false");
            settings.put("bootui.mcp.enabled", "ON");
            settings.put("bootui.cli.enabled", "ON");
            settings.put("bootui.sql-trace.enabled", "false");
            settings.put("bootui.overrides-file", "target/mongodb-http/" + type + custom + "/overrides.properties");
            settings.put("bootui.mongodb.clients.sync.databases", MongoDbLiveFixture.DATABASE);
            settings.put("bootui.mongodb.clients.reactive.databases", MongoDbLiveFixture.DATABASE);
            if (custom) {
                settings.put("bootui.path", "/console");
                settings.put("bootui.api-path", "/diagnostics");
                settings.put("server.servlet.context-path", "/host");
                settings.put("spring.webflux.base-path", "/host");
            }
            application.setDefaultProperties(settings);
            application.addInitializers(context -> {
                context.getBeanFactory().registerSingleton("sync", sync);
                context.getBeanFactory().registerSingleton("reactive", reactive);
                var definition =
                        new org.springframework.beans.factory.support.RootBeanDefinition(LazyMongoFactory.class);
                definition.setLazyInit(true);
                ((org.springframework.beans.factory.support.BeanDefinitionRegistry) context.getBeanFactory())
                        .registerBeanDefinition("lazyMongoClient", definition);
            });
            try (var context = application.run()) {
                String origin = "http://127.0.0.1:"
                        + ((WebServerApplicationContext) context).getWebServer().getPort();
                String api = custom ? "/host/diagnostics" : "/bootui/api";
                BootUiHttpProbe probe = new BootUiHttpProbe(origin);
                fixture.commands.clear();
                int lazyFactories = LAZY_FACTORIES.get();
                int pools = fixture.pools.get();
                assertThat(probe.get(api + "/panels").status()).isEqualTo(200);
                var initial = probe.get(api + "/mongodb");
                assertThat(initial.status()).as(initial.body()).isEqualTo(200);
                assertThat(initial.json().path("status").asText()).isEqualTo("NOT_READ");
                assertThat(probe.get(api + "/cli").status()).isEqualTo(200);
                assertThat(fixture.commands).isEmpty();
                assertThat(LAZY_FACTORIES).hasValue(lazyFactories);
                for (var client : initial.json().path("inventory").path("clients")) {
                    if (!client.path("inspectable").asBoolean()) continue;
                    MongoDbReportContract.verify(
                            probe, origin, api, client.path("id").asText(), "orders", "tenant_created");
                }
                io.github.jdubois.bootui.conformance.MongoDbProcessContract.browser(
                        origin, custom ? "/host/console" : "/bootui", true);
                assertThat(fixture.pools).hasValue(pools);
                int count = fixture.commands.size();
                var policy = context.getBean(BootUiProperties.class);
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Origin", origin);
                headers.put("Content-Type", "application/json");
                probe.cookie("XSRF-TOKEN").ifPresent(token -> headers.put("X-XSRF-TOKEN", token));
                policy.setReadOnly(true);
                assertThat(probe.request("POST", api + "/mongodb/inspect", headers, "{}")
                                .status())
                        .isEqualTo(403);
                assertThat(probe.request("POST", api + "/cli/tools/mongodb_inspect", headers, "{}")
                                .status())
                        .isEqualTo(403);
                assertThat(probe.request("POST", api + "/cli/tools/get_mongodb_report", headers, "{}")
                                .status())
                        .isEqualTo(200);
                policy.setReadOnly(false);
                policy.panel("mongodb").setReadOnly(true);
                assertThat(probe.request("POST", api + "/mongodb/inspect", headers, "{}")
                                .status())
                        .isEqualTo(403);
                context.getEnvironment()
                        .getPropertySources()
                        .addFirst(new MapPropertySource(
                                "mongo-exposure", Map.of("bootui.expose-values", "METADATA_ONLY")));
                assertThat(probe.get(api + "/mongodb").json().path("status").asText())
                        .isEqualTo("NOT_READ");
                policy.panel("mongodb").setEnabled(false);
                assertThat(probe.get(api + "/mongodb").status()).isEqualTo(403);
                assertThat(fixture.commands).hasSize(count);
                if (custom)
                    assertThat(probe.get("/bootui/api/mongodb").status()).isEqualTo(404);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class HttpConfiguration {}

    public static class LazyMongoFactory
            implements org.springframework.beans.factory.FactoryBean<com.mongodb.client.MongoClient> {
        public LazyMongoFactory() {
            LAZY_FACTORIES.incrementAndGet();
        }

        @Override
        public com.mongodb.client.MongoClient getObject() {
            throw new AssertionError("Lazy Mongo client was initialized");
        }

        @Override
        public Class<?> getObjectType() {
            return com.mongodb.client.MongoClient.class;
        }
    }

    /** The normal unit-test index is intentionally just "bootui-test-index". Live browser cases
     * select the real built UI without replacing that resource for other test classes. */
    private static final class BuiltUiResources implements AutoCloseable {
        private final ClassLoader previous = Thread.currentThread().getContextClassLoader();

        BuiltUiResources() {
            var directory = io.github.jdubois.bootui.conformance.MongoDbProcessContract.root()
                    .resolve("bootui-ui/target/classes/META-INF/resources/bootui")
                    .toAbsolutePath()
                    .normalize();
            assertThat(java.nio.file.Files.isRegularFile(directory.resolve("index.html")))
                    .isTrue();
            Thread.currentThread().setContextClassLoader(new ClassLoader(previous) {
                @Override
                public java.net.URL getResource(String name) {
                    String prefix = "META-INF/resources/bootui/";
                    if (!name.startsWith(prefix)) return super.getResource(name);
                    var file =
                            directory.resolve(name.substring(prefix.length())).normalize();
                    if (!file.startsWith(directory) || !java.nio.file.Files.exists(file)) return null;
                    try {
                        return file.toUri().toURL();
                    } catch (java.net.MalformedURLException ex) {
                        throw new IllegalStateException(ex);
                    }
                }

                @Override
                public java.util.Enumeration<java.net.URL> getResources(String name) throws java.io.IOException {
                    if (!name.startsWith("META-INF/resources/bootui/")) return super.getResources(name);
                    var resource = getResource(name);
                    return java.util.Collections.enumeration(
                            resource == null ? java.util.List.of() : java.util.List.of(resource));
                }
            });
        }

        @Override
        public void close() {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }
}
