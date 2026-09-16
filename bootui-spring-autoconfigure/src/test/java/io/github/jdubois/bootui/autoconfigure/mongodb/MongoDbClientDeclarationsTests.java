package io.github.jdubois.bootui.autoconfigure.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.client.MongoClient;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.mock.env.MockEnvironment;

class MongoDbClientDeclarationsTests {
    @Test
    void lazyPrototypeProxyFactoryAliasesAndParentAreNeverResolved() {
        AtomicInteger creations = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        DefaultListableBeanFactory parent = new DefaultListableBeanFactory();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory(parent);
        Object proxy = Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {MongoClient.class}, (object, method, arguments) -> {
                    calls.incrementAndGet();
                    throw new AssertionError("Proxy invoked");
                });
        parent.registerSingleton("parentClient", proxy);
        parent.registerAlias("parentClient", "alias");
        RootBeanDefinition lazy = new RootBeanDefinition(MongoClient.class, () -> {
            creations.incrementAndGet();
            throw new AssertionError("Lazy client created");
        });
        lazy.setLazyInit(true);
        beans.registerBeanDefinition("lazyClient", lazy);
        RootBeanDefinition prototype = new RootBeanDefinition(MongoClient.class, () -> {
            creations.incrementAndGet();
            throw new AssertionError("Prototype created");
        });
        prototype.setScope("prototype");
        beans.registerBeanDefinition("prototypeClient", prototype);
        RootBeanDefinition factory = new RootBeanDefinition(UncreatedClientFactory.class);
        factory.setLazyInit(true);
        beans.registerBeanDefinition("factoryClient", factory);
        var result = MongoDbClientDeclarations.discover(
                beans,
                List.of(SpringSyncMongoDbClientAccess.reader()),
                new MockEnvironment().withProperty("bootui.mongodb.clients.parentClient.databases", "orders"),
                16);
        assertThat(result.clients()).hasSize(4);
        assertThat(result.clients()).allMatch(client -> client.access() == null);
        assertThat(result.clients())
                .filteredOn(client -> client.name().equals("parentClient"))
                .singleElement()
                .satisfies(client -> {
                    assertThat(client.lifecycle()).isEqualTo("UNRESOLVED");
                    assertThat(client.databases()).containsExactly("orders");
                });
        assertThat(creations).hasValue(0);
        assertThat(calls).hasValue(0);
    }

    @Test
    void configuredScopeDoesNotGuessFromUrisOrAuthenticationDatabase() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        RootBeanDefinition lazy = new RootBeanDefinition(MongoClient.class);
        lazy.setLazyInit(true);
        beans.registerBeanDefinition("client", lazy);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.mongodb.uri", "mongodb://user:SECRET_SENTINEL@localhost/authDb")
                .withProperty("spring.mongodb.database", "application");
        var result = MongoDbClientDeclarations.discover(
                beans, List.of(SpringSyncMongoDbClientAccess.reader()), environment, 16);
        assertThat(result.clients().get(0).databases()).isEmpty();
        assertThat(result.toString()).doesNotContain("SECRET_SENTINEL", "authDb");
        environment.setProperty("bootui.mongodb.clients.client.databases", "orders, customers");
        assertThatThrownBy(() -> MongoDbClientDeclarations.discover(
                        beans, List.of(SpringSyncMongoDbClientAccess.reader()), environment, 16))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid MongoDB configured database scope");
    }

    @Test
    void scopeValidationCountsOnlyRelevantKeysAndUsesResolvedPropertySourcePrecedence() {
        MockEnvironment environment = new MockEnvironment();
        java.util.Map<String, Object> unrelated = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 4097; i++) unrelated.put("unrelated.setting." + i, "ordinary");
        unrelated.put("bootui.mongodb.clients.client.databases", "orders,audit");
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("top", unrelated));
        environment.setProperty("bootui.mongodb.clients.client.databases", "shadowed,shadowed");
        assertThat(MongoDbClientDeclarations.scopes(environment)).containsEntry("client", List.of("orders", "audit"));
        for (String invalid : List.of("orders,orders", "orders, audit", "orders.", "orders/", "a".repeat(64))) {
            environment.getPropertySources().remove("top");
            environment.setProperty("bootui.mongodb.clients.client.databases", invalid);
            assertThatThrownBy(() -> MongoDbClientDeclarations.scopes(environment))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    static class UncreatedClientFactory implements FactoryBean<MongoClient> {
        UncreatedClientFactory() {
            throw new AssertionError("Factory created");
        }

        public MongoClient getObject() {
            throw new AssertionError("Factory product requested");
        }

        public Class<?> getObjectType() {
            return MongoClient.class;
        }
    }
}
