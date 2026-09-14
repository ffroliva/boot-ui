package io.github.jdubois.bootui.autoconfigure.datasource;

import io.github.jdubois.bootui.engine.sqltrace.SqlTracedDataSource;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;

/** Reads only existing datasource objects; sidebar discovery must not initialize a lazy pool. */
public final class DataSourceDeclarations {

    private DataSourceDeclarations() {}

    public record Snapshot(boolean present, boolean incomplete, List<DataSource> sources) {
        public Snapshot {
            sources = List.copyOf(sources);
        }
    }

    public static Snapshot inspect(ApplicationContext context) {
        String[] names = context.getBeanNamesForType(DataSource.class, true, false);
        if (names.length == 0) {
            return new Snapshot(false, false, List.of());
        }
        if (!(context.getAutowireCapableBeanFactory() instanceof ConfigurableListableBeanFactory factory)) {
            return new Snapshot(true, true, List.of());
        }
        ArrayDeque<DataSource> pending = new ArrayDeque<>();
        boolean incomplete = false;
        for (String name : names) {
            Object singleton = factory.getSingleton(name);
            if (singleton instanceof DataSource dataSource) {
                pending.add(dataSource);
            } else {
                incomplete = true;
            }
        }
        Set<DataSource> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<DataSource> sources = new ArrayList<>();
        while (!pending.isEmpty() && seen.size() < 64) {
            DataSource source = pending.removeFirst();
            if (!seen.add(source)) {
                continue;
            }
            if (source instanceof SqlTracedDataSource) {
                try {
                    DataSource target = source.unwrap(DataSource.class);
                    if (target != null && target != source) {
                        pending.add(target);
                        continue;
                    }
                } catch (SQLException | RuntimeException ex) {
                    incomplete = true;
                }
            }
            if (DelegatingDataSources.isRouting(source.getClass())) {
                var targets = DelegatingDataSources.routingTargets(source);
                if (!targets.isEmpty()) {
                    pending.addAll(targets.values());
                    continue;
                }
                incomplete = true;
            } else if (DelegatingDataSources.isSingleTarget(source.getClass())) {
                DataSource target = DelegatingDataSources.target(source);
                if (target != null) {
                    pending.add(target);
                    continue;
                }
                incomplete = true;
            }
            sources.add(source);
        }
        return new Snapshot(true, incomplete || !pending.isEmpty(), sources);
    }
}
