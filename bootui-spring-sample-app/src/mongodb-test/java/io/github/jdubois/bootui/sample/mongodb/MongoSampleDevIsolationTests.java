package io.github.jdubois.bootui.sample.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import io.github.jdubois.bootui.sample.BootUiSampleApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;

@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/mongodb-dev-isolation/overrides.properties"
        })
class MongoSampleDevIsolationTests {

    @Autowired
    ApplicationContext context;

    @Test
    void compiledProfileDoesNotCreateMongoClientsInTheOrdinaryDevApp() {
        assertThat(context.getBeansOfType(MongoClient.class)).isEmpty();
        assertThat(context.getBeansOfType(MongoTemplate.class)).isEmpty();
        assertThat(context.getBeansOfType(SampleMongoProductRepository.class)).isEmpty();
    }
}
