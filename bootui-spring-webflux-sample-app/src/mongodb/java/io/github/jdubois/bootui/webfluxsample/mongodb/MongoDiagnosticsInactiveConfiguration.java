package io.github.jdubois.bootui.webfluxsample.mongodb;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoReactiveAutoConfiguration;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoReactiveRepositoriesAutoConfiguration;
import org.springframework.boot.mongodb.autoconfigure.MongoReactiveAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Compiling the optional profile must not activate a default localhost Mongo client in dev tests. */
@Configuration(proxyBeanMethods = false)
@Profile("!mongodb-diagnostics")
@EnableAutoConfiguration(
        exclude = {
            MongoReactiveAutoConfiguration.class,
            DataMongoReactiveAutoConfiguration.class,
            DataMongoReactiveRepositoriesAutoConfiguration.class
        })
class MongoDiagnosticsInactiveConfiguration {}
