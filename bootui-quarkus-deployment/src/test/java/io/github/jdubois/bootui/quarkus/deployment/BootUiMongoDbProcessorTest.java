package io.github.jdubois.bootui.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.ExcludedTypeBuildItem;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.builditem.RunTimeConfigurationDefaultBuildItem;
import io.quarkus.runtime.LaunchMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BootUiMongoDbProcessorTest {
    @ParameterizedTest
    @EnumSource(LaunchMode.class)
    void optionalClassesAreExcludedWithoutNativeCapabilityAndProductionIsAlwaysDark(LaunchMode mode) {
        for (boolean mongo : List.of(false, true)) {
            List<AdditionalBeanBuildItem> beans = new ArrayList<>();
            List<ExcludedTypeBuildItem> excluded = new ArrayList<>();
            List<RunTimeConfigurationDefaultBuildItem> defaults = new ArrayList<>();
            new BootUiMongoDbProcessor()
                    .register(
                            new LaunchModeBuildItem(mode, Optional.empty(), false, Optional.empty(), false),
                            new Capabilities(mongo ? Set.of(Capability.MONGODB_CLIENT) : Set.of()),
                            beans::add,
                            excluded::add,
                            defaults::add);
            assertThat(defaults).hasSize(1);
            if (mongo && mode != LaunchMode.NORMAL) {
                assertThat(beans)
                        .singleElement()
                        .satisfies(bean ->
                                assertThat(bean.getBeanClasses()).containsExactly(BootUiMongoDbProcessor.PRODUCER));
                assertThat(excluded).isEmpty();
            } else {
                assertThat(beans).isEmpty();
                assertThat(excluded)
                        .hasSize(BootUiMongoDbProcessor.OPTIONAL_TYPES.size() + (mode == LaunchMode.NORMAL ? 2 : 0));
            }
        }
    }

    @org.junit.jupiter.api.Test
    void neverConsumesClientBuildItemsOrRegistersAnyApplicationClient() {
        for (var method : BootUiMongoDbProcessor.class.getDeclaredMethods()) {
            assertThat(method.toGenericString())
                    .doesNotContain("MongoClientBuildItem", "MongoUnremovableClientsBuildItem");
        }
        assertThat(BootUiMongoDbProcessor.OPTIONAL_TYPES)
                .noneMatch(type -> type.startsWith("com.mongodb.") || type.startsWith("io.quarkus.mongodb."));
    }
}
