package io.github.jdubois.bootui.sample.mongodb;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoRepositoriesAutoConfiguration;
import org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Keeps dev Docker-free even when the optional Mongo source/dependency profile was compiled. */
@Configuration(proxyBeanMethods = false)
@Profile("!docker-mongodb")
@EnableAutoConfiguration(
        exclude = {
            MongoAutoConfiguration.class,
            DataMongoAutoConfiguration.class,
            DataMongoRepositoriesAutoConfiguration.class
        })
class MongoSampleInactiveConfiguration {}
