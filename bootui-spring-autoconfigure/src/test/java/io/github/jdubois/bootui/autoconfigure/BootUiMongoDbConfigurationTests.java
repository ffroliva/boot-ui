package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.github.jdubois.bootui.autoconfigure.idle.ConsoleActivityTracker;
import io.github.jdubois.bootui.autoconfigure.mongodb.MongoDbClientDeclarations;
import io.github.jdubois.bootui.autoconfigure.safety.PanelAccessFilter;
import io.github.jdubois.bootui.engine.mongodb.MongoDbSettings;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class BootUiMongoDbConfigurationTests {
    @Test
    void servletBodyFilterOwnsTheSlotAfterActivityAndOnlyMapsTheConfiguredAction() {
        var configuration = new BootUiAutoConfiguration();
        for (String apiPath : List.of("/bootui/api", "/internal/console")) {
            var properties = new BootUiProperties();
            properties.setApiPath(apiPath);
            var registration =
                    new BootUiMongoDbConfiguration.ServletTransport().bootUiMongoDbRequestSizeFilter(properties);
            var access = configuration.bootUiPanelAccessFilterRegistration(mock(PanelAccessFilter.class), properties);
            var activity = configuration.bootUiConsoleActivityFilterRegistration(
                    mock(ConsoleActivityTracker.class), properties);
            assertThat(access.getOrder()).isEqualTo(Integer.MIN_VALUE + 3);
            assertThat(activity.getOrder()).isEqualTo(Integer.MIN_VALUE + 4);
            assertThat(registration.getOrder()).isEqualTo(Integer.MIN_VALUE + 5).isGreaterThan(activity.getOrder());
            assertThat(registration.getUrlPatterns()).containsExactly(apiPath + "/mongodb/inspect");
        }
    }

    @Test
    void sharedDefaultsAndAllSettingsMapWithoutASecondBindingModel() {
        BootUiMongoDbConfiguration configuration = new BootUiMongoDbConfiguration();
        assertThat(configuration.bootUiMongoDbSettings(new MockEnvironment())).isEqualTo(MongoDbSettings.defaults());
        var settings = configuration.bootUiMongoDbSettings(new MockEnvironment()
                .withProperty("bootui.mongodb.max-clients", "3")
                .withProperty("bootui.mongodb.max-databases", "2")
                .withProperty("bootui.mongodb.max-collections-per-database", "7")
                .withProperty("bootui.mongodb.max-indexes-per-collection", "5")
                .withProperty("bootui.mongodb.max-total-items", "80")
                .withProperty("bootui.mongodb.max-metadata-bytes", "32768")
                .withProperty("bootui.mongodb.max-text-length", "128")
                .withProperty("bootui.mongodb.timeout-ms", "2000")
                .withProperty("bootui.mongodb.operation-timeout-ms", "250")
                .withProperty("bootui.mongodb.inspect-enabled", "false")
                .withProperty("bootui.mongodb.authorized-database-enumeration-enabled", "true"));
        assertThat(settings).isEqualTo(new MongoDbSettings(3, 2, 7, 5, 80, 32768, 128, 2000, 250, false, true));
    }

    @Test
    void invalidLimitsAndScopeAreRejectedWithoutEchoingInput() {
        for (String value : List.of("", "0", "-1", "2147483648", "SECRET_SENTINEL")) {
            assertThatThrownBy(() -> new BootUiMongoDbConfiguration()
                            .bootUiMongoDbSettings(
                                    new MockEnvironment().withProperty("bootui.mongodb.timeout-ms", value)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("SECRET_SENTINEL");
        }
        assertThatThrownBy(() -> MongoDbClientDeclarations.scopes(new MockEnvironment()
                        .withProperty("bootui.mongodb.clients.app.databases", "mongodb://SECRET_SENTINEL")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("SECRET_SENTINEL");
    }
}
