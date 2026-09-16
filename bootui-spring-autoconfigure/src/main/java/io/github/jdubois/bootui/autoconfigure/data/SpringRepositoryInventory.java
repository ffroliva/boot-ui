package io.github.jdubois.bootui.autoconfigure.data;

import io.github.jdubois.bootui.core.dto.RepositoryDiscoveryDto;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.springframework.aop.SpringProxy;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.support.RepositoryFactoryInformation;

/** Reads already-computed repository factory metadata without requesting a repository or factory bean. */
public final class SpringRepositoryInventory {
    public record Entry(String beanName, RepositoryInformation information) {}

    public record DiscoveryResult(List<Entry> entries, RepositoryDiscoveryDto discovery) {
        public DiscoveryResult {
            entries = List.copyOf(entries);
        }
    }

    public DiscoveryResult discover(ListableBeanFactory root) {
        List<Entry> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> factories = Collections.newSetFromMap(new IdentityHashMap<>());
        BeanFactory current = root;
        int visited = 0;
        boolean truncated = false;
        if (!(root instanceof ConfigurableListableBeanFactory)) {
            return new DiscoveryResult(
                    List.of(),
                    new RepositoryDiscoveryDto(
                            false, false, List.of("Non-creating singleton repository discovery is unavailable.")));
        }
        while (current instanceof ConfigurableListableBeanFactory local && factories.add(local)) {
            String[] names;
            try {
                names = local.getBeanNamesForType(RepositoryFactoryInformation.class, true, false);
            } catch (RuntimeException | LinkageError ex) {
                warning(warnings, "Repository declaration discovery failed.");
                break;
            }
            java.util.Arrays.sort(names);
            for (String candidate : names) {
                if (++visited > 4096 || entries.size() == 512) {
                    truncated = true;
                    break;
                }
                String name = candidate.startsWith("&") ? candidate.substring(1) : candidate;
                if (name.length() > 256) {
                    warning(warnings, "An oversized repository declaration was omitted.");
                    continue;
                }
                Object singleton = local.getSingleton(name);
                if (singleton == null) {
                    warning(warnings, "Uninitialized or non-singleton repository factories were not resolved.");
                    continue;
                }
                if (!seen.add(singleton)) continue;
                if (Proxy.isProxyClass(singleton.getClass())
                        || singleton instanceof SpringProxy
                        || !(singleton instanceof RepositoryFactoryInformation<?, ?> info)) {
                    warning(warnings, "A proxy or unsupported repository factory was not resolved.");
                    continue;
                }
                try {
                    RepositoryInformation information = info.getRepositoryInformation();
                    if (information == null) {
                        warning(warnings, "Repository metadata is not initialized.");
                    } else {
                        Class<?> repositoryInterface = information.getRepositoryInterface();
                        if (repositoryInterface != null && repositoryInterface.getMethods().length > 256) {
                            warning(warnings, "Repository method metadata is limited to 256 methods per repository.");
                        }
                        entries.add(new Entry(name, information));
                    }
                } catch (RuntimeException | LinkageError ex) {
                    warning(warnings, "Repository metadata could not be read.");
                }
            }
            if (truncated) break;
            current = local.getParentBeanFactory();
        }
        if (truncated) warning(warnings, "Repository discovery limit reached.");
        return new DiscoveryResult(entries, new RepositoryDiscoveryDto(warnings.isEmpty(), truncated, warnings));
    }

    private static void warning(List<String> warnings, String value) {
        if (warnings.size() < 32 && !warnings.contains(value)) warnings.add(value);
    }
}
