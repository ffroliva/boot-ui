package io.github.jdubois.bootui.autoconfigure.mongodb;

import io.github.jdubois.bootui.engine.mongodb.MongoDbScopes;
import io.github.jdubois.bootui.spi.MongoDbProvider;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.aop.SpringProxy;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;

/** Observes existing singleton objects; never resolves a FactoryBean product or a proxy target. */
public final class MongoDbClientDeclarations {
    private static final int MAX_DECLARATIONS = 4096;

    public interface Reader {
        Class<?> clientType();

        String style();

        boolean supports(Object client);

        MongoDbProvider.Client initialized(String key, String name, Object client, List<String> databases);
    }

    private MongoDbClientDeclarations() {}

    public static MongoDbProvider.Discovery discover(
            ConfigurableListableBeanFactory factory, List<Reader> readers, Environment environment, int maxClients) {
        return discover(factory, readers, readers.isEmpty() ? Map.of() : scopes(environment), maxClients);
    }

    public static MongoDbProvider.Discovery discover(
            ConfigurableListableBeanFactory factory,
            List<Reader> readers,
            Map<String, List<String>> scopes,
            int maxClients) {
        List<MongoDbProvider.Client> clients = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        Set<Object> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<BeanFactory> factories = Collections.newSetFromMap(new IdentityHashMap<>());
        int visited = 0;
        boolean truncated = false;
        BeanFactory current = factory;
        while (current instanceof ConfigurableListableBeanFactory local && factories.add(current)) {
            for (Reader reader : readers) {
                String[] names;
                try {
                    names = local.getBeanNamesForType(reader.clientType(), true, false);
                } catch (RuntimeException | LinkageError ex) {
                    addLimitation(limitations, "Client declaration discovery failed.");
                    continue;
                }
                java.util.Arrays.sort(names);
                for (String name : names) {
                    if (++visited > MAX_DECLARATIONS || clients.size() >= maxClients) {
                        truncated = true;
                        break;
                    }
                    if (name.length() > 256 || name.chars().anyMatch(Character::isISOControl)) {
                        addLimitation(limitations, "An unsupported client declaration name was omitted.");
                        continue;
                    }
                    Object singleton = local.getSingleton(name);
                    if (singleton != null && !identities.add(singleton)) {
                        continue;
                    }
                    String key = factories.size() + ":" + reader.style() + ":" + name;
                    List<String> databases = scopes.getOrDefault(name, List.of());
                    if (singleton == null || !reader.clientType().isInstance(singleton)) {
                        clients.add(new MongoDbProvider.Client(
                                key,
                                name,
                                reader.style(),
                                null,
                                "NOT_INITIALIZED",
                                databases,
                                List.of(),
                                null,
                                List.of("Client is not an initialized singleton."),
                                null,
                                null));
                    } else if (Proxy.isProxyClass(singleton.getClass())
                            || singleton instanceof SpringProxy
                            || !reader.supports(singleton)) {
                        clients.add(new MongoDbProvider.Client(
                                key,
                                name,
                                reader.style(),
                                null,
                                "UNRESOLVED",
                                databases,
                                List.of(),
                                null,
                                List.of("Custom or proxy client binding is not inspected."),
                                singleton,
                                null));
                    } else {
                        try {
                            clients.add(reader.initialized(key, name, singleton, databases));
                        } catch (RuntimeException | LinkageError ex) {
                            clients.add(new MongoDbProvider.Client(
                                    key,
                                    name,
                                    reader.style(),
                                    null,
                                    "UNRESOLVED",
                                    databases,
                                    List.of(),
                                    null,
                                    List.of("Local client metadata is unavailable."),
                                    singleton,
                                    null));
                        }
                    }
                }
                if (truncated) break;
            }
            if (truncated) break;
            current = local.getParentBeanFactory();
        }
        if (visited > MAX_DECLARATIONS) addLimitation(limitations, "Client declaration traversal limit reached.");
        return new MongoDbProvider.Discovery(clients, limitations, truncated);
    }

    public static Map<String, List<String>> scopes(Environment environment) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (environment instanceof ConfigurableEnvironment configurable) {
            Set<String> seen = new java.util.HashSet<>();
            for (var source : configurable.getPropertySources()) {
                if (!(source instanceof EnumerablePropertySource<?> enumerable)) continue;
                for (String property : enumerable.getPropertyNames()) {
                    String prefix = MongoDbScopes.PREFIX;
                    String suffix = MongoDbScopes.SUFFIX;
                    if (property.startsWith(prefix) && property.endsWith(suffix) && seen.add(property)) {
                        String client = MongoDbScopes.clientName(
                                property.substring(prefix.length(), property.length() - suffix.length()));
                        if (result.size() >= MongoDbScopes.MAX_CLIENTS) {
                            throw new IllegalArgumentException("Too many MongoDB client scopes");
                        }
                        if (result.putIfAbsent(client, MongoDbScopes.parse(environment.getProperty(property)))
                                != null) {
                            throw new IllegalArgumentException("Duplicate MongoDB client scope");
                        }
                    }
                }
            }
        }
        return Map.copyOf(result);
    }

    private static void addLimitation(List<String> limitations, String value) {
        if (limitations.size() < 32 && !limitations.contains(value)) limitations.add(value);
    }
}
