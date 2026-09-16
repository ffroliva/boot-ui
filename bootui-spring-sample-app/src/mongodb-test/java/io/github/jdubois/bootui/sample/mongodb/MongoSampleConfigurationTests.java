package io.github.jdubois.bootui.sample.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.mongodb.autoconfigure.MongoConnectionDetails;

class MongoSampleConfigurationTests {

    @Test
    void composeRootCredentialsAreReplacedBeforeClientCreationWithoutLosingTheMappedPort() {
        MongoConnectionDetails details = () ->
                new ConnectionString("mongodb://root:root-fixture@127.0.0.1:49157/bootui_sample?authSource=admin");
        var settings = MongoSampleConfiguration.applicationSettings(
                MongoClientSettings.builder(), details, "fixture_user", "fixture_password");
        assertThat(settings.getClusterSettings().getHosts()).singleElement().satisfies(address -> {
            assertThat(address.getHost()).isEqualTo("127.0.0.1");
            assertThat(address.getPort()).isEqualTo(49157);
        });
        assertThat(settings.getCredential()).isNotNull();
        assertThat(settings.getCredential().getUserName()).isEqualTo("fixture_user");
        assertThat(settings.getCredential().getSource()).isEqualTo("bootui_sample");
        assertThat(settings.getCredential().getPassword()).containsExactly("fixture_password".toCharArray());
        assertThat(settings.getTimeout(TimeUnit.MILLISECONDS)).isEqualTo(2000);
        assertThat(settings.getConnectionPoolSettings().getMaxSize()).isEqualTo(4);
    }

    @Test
    void emptyApplicationCredentialsFailBeforeClientCreation() {
        MongoConnectionDetails details = () -> new ConnectionString("mongodb://127.0.0.1:49157");
        assertThatThrownBy(() -> MongoSampleConfiguration.applicationSettings(
                        MongoClientSettings.builder(), details, "", "fixture"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void workloadHasFixedWritesAndBoundedRepositoryReads() {
        var repository = mock(SampleMongoProductRepository.class);
        when(repository.findTop3ByIdInAndAvailableTrueOrderBySkuAsc(List.of("starter", "guide", "kit")))
                .thenReturn(List.of());
        when(repository.countSampleCategories())
                .thenReturn(List.of(new SampleMongoProductRepository.CategoryCount("books", 1)));
        var summary = new SampleMongoWorkload(repository).run();
        assertThat(summary.database()).isEqualTo("bootui_sample");
        assertThat(summary.upserted()).isEqualTo(3);
        assertThat(summary.categories()).hasSize(1);
        verify(repository).saveAll(anyIterable());
        verify(repository).findTop3ByIdInAndAvailableTrueOrderBySkuAsc(List.of("starter", "guide", "kit"));
        verify(repository).countSampleCategories();
    }
}
