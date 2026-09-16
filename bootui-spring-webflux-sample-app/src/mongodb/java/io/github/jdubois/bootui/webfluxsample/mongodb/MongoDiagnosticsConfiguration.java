package io.github.jdubois.bootui.webfluxsample.mongodb;

import java.util.concurrent.TimeUnit;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;

@Configuration(proxyBeanMethods = false)
@Profile("mongodb-diagnostics")
@EnableReactiveMongoRepositories(basePackageClasses = SampleReactiveMongoRepository.class)
public class MongoDiagnosticsConfiguration {

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    MongoClientSettingsBuilderCustomizer sampleReactiveMongoBounds() {
        return builder -> builder.applicationName("bootui-webflux-mongodb-sample")
                .applyToClusterSettings(settings -> settings.serverSelectionTimeout(2, TimeUnit.SECONDS))
                .applyToSocketSettings(
                        settings -> settings.connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS))
                .applyToConnectionPoolSettings(settings -> settings.minSize(0).maxSize(4))
                .timeout(2, TimeUnit.SECONDS);
    }
}
