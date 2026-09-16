package io.github.jdubois.bootui.quarkus.deployment;

import io.github.jdubois.bootui.quarkus.QuarkusPanelAvailability;
import io.github.jdubois.bootui.quarkus.mongodb.MongoDbClientsRecorder;
import io.github.jdubois.bootui.quarkus.mongodb.MongoDbClientsSnapshot.Declaration;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.BeanContainerBuildItem;
import io.quarkus.arc.deployment.ExcludedTypeBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem;
import io.quarkus.arc.processor.BeanInfo;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.builditem.RunTimeConfigurationDefaultBuildItem;
import io.quarkus.deployment.builditem.ServiceStartBuildItem;
import io.quarkus.runtime.LaunchMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jboss.jandex.DotName;

/** No dependency on Mongo deployment APIs: observing their client build item would retain all clients. */
class BootUiMongoDbProcessor {
    static final String PRODUCER = "io.github.jdubois.bootui.quarkus.BootUiMongoDbProducer";
    static final List<String> OPTIONAL_TYPES = List.of(
            PRODUCER,
            "io.github.jdubois.bootui.quarkus.mongodb.QuarkusMongoDbProvider",
            "io.github.jdubois.bootui.quarkus.mongodb.QuarkusSyncMongoDbClientAccess",
            "io.github.jdubois.bootui.quarkus.mongodb.QuarkusReactiveMongoDbClientAccess",
            "io.github.jdubois.bootui.quarkus.mongodb.MongoDbSyncCursor",
            "io.github.jdubois.bootui.quarkus.mongodb.MongoDbReactiveCursor",
            "io.github.jdubois.bootui.quarkus.mongodb.MongoDbDriverValues");
    private static final DotName SYNC = DotName.createSimple("com.mongodb.client.MongoClient");
    private static final DotName REACTIVE = DotName.createSimple("io.quarkus.mongodb.reactive.ReactiveMongoClient");
    private static final DotName MONGO_NAME = DotName.createSimple("io.quarkus.mongodb.MongoClientName");
    private static final DotName NAMED = DotName.createSimple("jakarta.inject.Named");

    @BuildStep
    void register(
            LaunchModeBuildItem mode,
            Capabilities capabilities,
            BuildProducer<AdditionalBeanBuildItem> beans,
            BuildProducer<ExcludedTypeBuildItem> excluded,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> defaults) {
        boolean present = present(mode, capabilities);
        defaults.produce(new RunTimeConfigurationDefaultBuildItem(
                QuarkusPanelAvailability.MONGODB_PRESENT_KEY, Boolean.toString(present)));
        if (present) {
            beans.produce(AdditionalBeanBuildItem.builder()
                    .addBeanClass(PRODUCER)
                    .setUnremovable()
                    .build());
        } else {
            OPTIONAL_TYPES.forEach(type -> excluded.produce(new ExcludedTypeBuildItem(type)));
        }
        if (mode.getLaunchMode() == LaunchMode.NORMAL) {
            excluded.produce(new ExcludedTypeBuildItem("io.github.jdubois.bootui.quarkus.web.MongoDbResource"));
            excluded.produce(
                    new ExcludedTypeBuildItem("io.github.jdubois.bootui.quarkus.mongodb.MongoDbClientsSnapshot"));
        }
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void capture(
            LaunchModeBuildItem mode,
            Capabilities capabilities,
            ValidationPhaseBuildItem validation,
            BeanContainerBuildItem container,
            MongoDbClientsRecorder recorder,
            BuildProducer<ServiceStartBuildItem> starts) {
        if (!present(mode, capabilities)) return;
        List<Declaration> rows = new ArrayList<>();
        int visited = 0;
        boolean truncated = false;
        for (boolean removed : List.of(false, true)) {
            for (BeanInfo bean : removed
                    ? validation.getContext().removedBeans()
                    : validation.getContext().beans()) {
                if (++visited > 4096 || rows.size() == 64) {
                    truncated = true;
                    break;
                }
                Declaration row = declaration(bean, removed);
                if (row != null) rows.add(row);
            }
        }
        rows.sort(Comparator.comparing(Declaration::name)
                .thenComparing(Declaration::driverStyle)
                .thenComparing(Declaration::beanId));
        recorder.install(rows, truncated);
        starts.produce(new ServiceStartBuildItem("bootui-mongodb"));
    }

    static Declaration declaration(BeanInfo bean, boolean removed) {
        boolean sync = bean.getTypes().stream().anyMatch(type -> type.name().equals(SYNC));
        boolean reactive = bean.getTypes().stream().anyMatch(type -> type.name().equals(REACTIVE));
        if (!sync && !reactive) return null;
        String name = bean.getQualifier(MONGO_NAME)
                .map(value -> value.value().asString())
                .orElse(null);
        if (name == null) {
            name = bean.getQualifier(NAMED)
                    .map(value -> value.value().asString())
                    .orElse("default");
            if (reactive && bean.isSynthetic() && name.endsWith("reactive") && name.length() > "reactive".length()) {
                name = name.substring(0, name.length() - "reactive".length());
            }
        }
        return new Declaration(bean.getIdentifier(), name, sync ? "SYNC" : "REACTIVE", removed);
    }

    private static boolean present(LaunchModeBuildItem mode, Capabilities capabilities) {
        return mode.getLaunchMode() != LaunchMode.NORMAL && capabilities.isPresent(Capability.MONGODB_CLIENT);
    }
}
