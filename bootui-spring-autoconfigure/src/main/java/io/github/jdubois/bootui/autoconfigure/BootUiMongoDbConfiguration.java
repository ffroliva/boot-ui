package io.github.jdubois.bootui.autoconfigure;

import io.github.jdubois.bootui.autoconfigure.mongodb.MongoDbClientDeclarations;
import io.github.jdubois.bootui.autoconfigure.mongodb.MongoDbController;
import io.github.jdubois.bootui.autoconfigure.mongodb.SpringMongoDbProvider;
import io.github.jdubois.bootui.autoconfigure.mongodb.SpringReactiveMongoDbClientAccess;
import io.github.jdubois.bootui.autoconfigure.mongodb.SpringSyncMongoDbClientAccess;
import io.github.jdubois.bootui.engine.mongodb.MongoDbSettings;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.spi.MongoDbProvider;
import java.util.List;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/** Imported only by the active MVC/WebFlux configurations; each driver style has its own linkage gate. */
@Configuration(proxyBeanMethods = false)
@Import({
    MongoDbController.class,
    BootUiMongoDbConfiguration.Sync.class,
    BootUiMongoDbConfiguration.Reactive.class,
    BootUiMongoDbConfiguration.ServletTransport.class,
    BootUiMongoDbConfiguration.ReactiveTransport.class
})
public class BootUiMongoDbConfiguration {
    @Bean
    @ConditionalOnMissingBean
    MongoDbSettings bootUiMongoDbSettings(Environment environment) {
        return MongoDbSettings.from(environment::getProperty);
    }

    @Bean
    @ConditionalOnMissingBean(MongoDbProvider.class)
    SpringMongoDbProvider bootUiMongoDbProvider(
            ConfigurableListableBeanFactory factory,
            List<MongoDbClientDeclarations.Reader> readers,
            Environment environment) {
        return new SpringMongoDbProvider(factory, readers, environment);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.mongodb.client.MongoClient")
    static class Sync {
        @Bean
        MongoDbClientDeclarations.Reader bootUiSyncMongoDbReader() {
            return SpringSyncMongoDbClientAccess.reader();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.mongodb.reactivestreams.client.MongoClient")
    static class Reactive {
        @Bean
        MongoDbClientDeclarations.Reader bootUiReactiveMongoDbReader() {
            return SpringReactiveMongoDbClientAccess.reader();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class ServletTransport {
        /**
         * Owns slot {@code MIN_VALUE + 5}, after localhost (+1), authentication (+2), panel access (+3)
         * and console activity (+4). Only the MongoDB inspection action consumes a bounded body.
         */
        @Bean
        org.springframework.boot.web.servlet.FilterRegistrationBean<
                        io.github.jdubois.bootui.autoconfigure.mongodb.MongoDbRequestSizeFilter>
                bootUiMongoDbRequestSizeFilter(BootUiProperties properties) {
            var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
                    new io.github.jdubois.bootui.autoconfigure.mongodb.MongoDbRequestSizeFilter(properties));
            registration.addUrlPatterns(properties.getApiPath() + "/" + BootUiPanels.MONGODB + "/inspect");
            registration.setOrder(Integer.MIN_VALUE + 5);
            registration.setName("bootUiMongoDbRequestSizeFilter");
            return registration;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    static class ReactiveTransport {
        @Bean
        io.github.jdubois.bootui.autoconfigure.mongodb.ReactiveMongoDbRequestSizeFilter bootUiMongoDbRequestSizeFilter(
                BootUiProperties properties) {
            return new io.github.jdubois.bootui.autoconfigure.mongodb.ReactiveMongoDbRequestSizeFilter(properties);
        }
    }
}
