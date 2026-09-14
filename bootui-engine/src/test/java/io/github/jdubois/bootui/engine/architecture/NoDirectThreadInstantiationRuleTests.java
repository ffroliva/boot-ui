package io.github.jdubois.bootui.engine.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.core.dto.ArchitectureRuleResultDto;
import io.github.jdubois.bootui.engine.architecture.kotlinfixtures.KotlinCapturedThreadFactory;
import io.github.jdubois.bootui.engine.architecture.kotlinfixtures.KotlinNonFactoryThreadLambda;
import io.github.jdubois.bootui.engine.architecture.kotlinfixtures.KotlinScheduledThreadFactory;
import java.io.Serializable;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class NoDirectThreadInstantiationRuleTests {

    @ParameterizedTest
    @EnumSource(ArchitecturePlatform.class)
    void verifiedJavaAndKotlinFactoriesMayConstructThreads(ArchitecturePlatform platform) {
        for (Class<?> fixture : List.of(
                InlineFactory.class,
                CapturedFactory.class,
                FieldFactory.class,
                SubinterfaceFactory.class,
                LocalSubtypeFactory.class,
                IntersectionFactory.class,
                SerializableFactory.class,
                CovariantLambdaFactory.class,
                NeverInitializeFactory.class,
                KotlinScheduledThreadFactory.class,
                KotlinCapturedThreadFactory.class)) {
            ArchitectureRuleResultDto result = evaluate(platform, fixture);
            assertThat(result.status()).as(fixture.getSimpleName()).isEqualTo("PASS");
            assertThat(result.violationCount()).isZero();
            assertThat(result.sampleViolations()).isEmpty();
        }
    }

    @ParameterizedTest
    @EnumSource(ArchitecturePlatform.class)
    void otherFunctionalInterfacesAreNotThreadFactories(ArchitecturePlatform platform) {
        ArchitectureRuleResultDto result = evaluate(platform, OtherLambdas.class);
        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.violationCount()).isEqualTo(3);
        assertThat(result.sampleViolations())
                .anyMatch(sample -> sample.contains("runnable()"))
                .anyMatch(sample -> sample.contains("supplier()"))
                .anyMatch(sample -> sample.contains("function()"));
        ArchitectureRuleResultDto serializable = evaluate(platform, SerializableOtherLambda.class);
        assertThat(serializable.violationCount()).isEqualTo(1);
        assertThat(serializable.sampleViolations())
                .singleElement()
                .asString()
                .contains("supplier()")
                .doesNotContain("$deserializeLambda$");

        ArchitectureRuleResultDto kotlin = evaluate(platform, KotlinNonFactoryThreadLambda.class);
        assertThat(kotlin.violationCount()).isEqualTo(1);
        assertThat(kotlin.sampleViolations()).singleElement().asString().contains("ThreadFactoryFixtures.kt");
    }

    @ParameterizedTest
    @EnumSource(ArchitecturePlatform.class)
    void aFactoryDoesNotExemptOtherConstructionOnTheSameLine(ArchitecturePlatform platform) {
        ArchitectureRuleResultDto result = evaluate(platform, MixedConstruction.class);
        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations()).singleElement().asString().contains("mixed()");
        ArchitectureRuleResultDto sameTarget = evaluate(platform, MixedLambdaConstruction.class);
        assertThat(sameTarget.violationCount()).isEqualTo(1);
        assertThat(sameTarget.sampleViolations()).singleElement().asString().contains("mixed(java.lang.Runnable)");
    }

    @ParameterizedTest
    @EnumSource(ArchitecturePlatform.class)
    void aFactoryContractDoesNotExtendIntoAnUnrelatedNestedLambda(ArchitecturePlatform platform) {
        ArchitectureRuleResultDto result = evaluate(platform, NestedOtherLambda.class);
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations()).singleElement().asString().contains("newThread(java.lang.Runnable)");
    }

    @ParameterizedTest
    @EnumSource(ArchitecturePlatform.class)
    void returningAThreadOrReferencingAHelperDoesNotEstablishAFactoryImplementation(ArchitecturePlatform platform) {
        ArchitectureRuleResultDto result = evaluate(platform, OrdinaryMethods.class);
        assertThat(result.id()).isEqualTo("ARCH-CODE-017");
        assertThat(result.severity()).isEqualTo("MEDIUM");
        assertThat(result.violationCount()).isEqualTo(3);
        assertThat(result.sampleViolations())
                .anyMatch(sample -> sample.contains("returned()"))
                .anyMatch(sample -> sample.contains("newThread(java.lang.Runnable)"))
                .anyMatch(sample -> sample.contains("helper(java.lang.Runnable)"));
    }

    @ParameterizedTest
    @EnumSource(ArchitecturePlatform.class)
    void threadSubclassConstructorsWithArrayParametersStayReported(ArchitecturePlatform platform) {
        ArchitectureRuleResultDto result = evaluate(platform, DirectSubclass.class);
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations()).singleElement().asString().contains("Worker.<init>(");
    }

    private static ArchitectureRuleResultDto evaluate(ArchitecturePlatform platform, Class<?> fixture) {
        return new NoDirectThreadInstantiationRule()
                .evaluate(new ArchitectureContext(
                        new ClassFileImporter().importClasses(fixture), List.of(fixture.getPackageName()), platform));
    }

    static class InlineFactory {
        void executor() {
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "retry");
                thread.setDaemon(true);
                return thread;
            });
        }
    }

    static class CapturedFactory {
        ThreadFactory factory(String name) {
            return task -> new Thread(task, name);
        }
    }

    static class FieldFactory {
        final ThreadFactory factory = task -> new Thread(task);
    }

    interface CustomThreadFactory extends ThreadFactory {}

    static class SubinterfaceFactory {
        CustomThreadFactory factory() {
            return task -> new Thread(task);
        }
    }

    static class LocalSubtypeFactory {
        Object factory() {
            CustomThreadFactory local = task -> new Thread(task);
            return local;
        }
    }

    interface OtherFactory {
        Thread newThread(Runnable task);
    }

    static class IntersectionFactory {
        Object factory() {
            return (ThreadFactory & OtherFactory) task -> new Thread(task);
        }
    }

    static class SerializableFactory {
        ThreadFactory factory() {
            return (ThreadFactory & Serializable) task -> new Thread(task);
        }
    }

    static class Worker extends Thread {
        Worker(String[] names) {}
    }

    interface CovariantThreadFactory extends ThreadFactory {
        @Override
        Worker newThread(Runnable task);
    }

    static class CovariantLambdaFactory {
        CovariantThreadFactory factory() {
            return task -> new Worker(new String[0]);
        }
    }

    static class DirectSubclass {
        Thread create() {
            return new Worker(new String[0]);
        }
    }

    static class NeverInitializeFactory {
        static final Object SENTINEL = failIfInitialized();

        private static Object failIfInitialized() {
            throw new AssertionError("Static bytecode analysis must not initialize application classes");
        }

        ThreadFactory factory() {
            return task -> new Thread(task);
        }
    }

    static class OtherLambdas {
        Runnable runnable() {
            return () -> new Thread().start();
        }

        Supplier<Thread> supplier() {
            return () -> new Thread();
        }

        Function<Runnable, Thread> function() {
            return task -> new Thread(task);
        }
    }

    static class SerializableOtherLambda {
        Supplier<Thread> supplier() {
            return (Supplier<Thread> & Serializable) () -> new Thread();
        }
    }

    static class MixedConstruction {
        void mixed() {
            accept(task -> new Thread(task), new Thread());
        }

        void accept(ThreadFactory factory, Thread unmanaged) {}
    }

    static class MixedLambdaConstruction {
        void mixed(Runnable task) {
            accept(r -> new Thread(r), () -> new Thread(task));
        }

        void accept(ThreadFactory factory, Supplier<Thread> unmanaged) {}
    }

    static class NestedOtherLambda implements ThreadFactory {
        @Override
        public Thread newThread(Runnable task) {
            Runnable unmanaged = () -> new Thread(task).start();
            return new Thread(unmanaged);
        }
    }

    static class OrdinaryMethods {
        Thread returned() {
            return new Thread();
        }

        Thread newThread(Runnable task) {
            return new Thread(task);
        }

        ThreadFactory reference() {
            return OrdinaryMethods::helper;
        }

        static Thread helper(Runnable task) {
            return new Thread(task);
        }
    }
}
