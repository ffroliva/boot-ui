package io.github.jdubois.bootui.sample.mongodb;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.boot.mongodb.autoconfigure.MongoConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@Configuration(proxyBeanMethods = false)
@Profile("docker-mongodb")
@EnableMongoRepositories(
        basePackageClasses = SampleMongoProductRepository.class,
        mongoTemplateRef = "sampleMongoTemplate")
public class MongoSampleConfiguration {

    public static final String DATABASE = "bootui_sample";

    @Bean(destroyMethod = "close")
    MongoClient sampleMongoClient(
            MongoConnectionDetails connectionDetails,
            ObjectProvider<MongoClientSettingsBuilderCustomizer> customizers,
            @Value("${sample.mongodb.username}") String username,
            @Value("${sample.mongodb.password}") String password) {
        var builder = MongoClientSettings.builder();
        customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
        return MongoClients.create(applicationSettings(builder, connectionDetails, username, password));
    }

    static MongoClientSettings applicationSettings(
            MongoClientSettings.Builder builder, MongoConnectionDetails details, String username, String password) {
        if (username.isBlank() || password.isBlank()) {
            throw new IllegalArgumentException("The Mongo sample requires its non-root fixture account");
        }
        // Compose supplies the dynamic endpoint but authenticates as root/admin. Override before creation.
        return builder.applyConnectionString(details.getConnectionString())
                .credential(MongoCredential.createCredential(username, DATABASE, password.toCharArray()))
                .applicationName("bootui-mongodb-sample")
                .applyToClusterSettings(settings -> settings.serverSelectionTimeout(2, TimeUnit.SECONDS))
                .applyToSocketSettings(
                        settings -> settings.connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS))
                .applyToConnectionPoolSettings(settings -> settings.minSize(0).maxSize(4))
                .timeout(2, TimeUnit.SECONDS)
                .build();
    }

    @Bean
    MongoTemplate sampleMongoTemplate(MongoClient sampleMongoClient) {
        return new MongoTemplate(sampleMongoClient, DATABASE);
    }
}
