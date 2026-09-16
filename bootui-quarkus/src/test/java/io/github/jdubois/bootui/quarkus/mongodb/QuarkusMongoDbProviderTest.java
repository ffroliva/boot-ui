package io.github.jdubois.bootui.quarkus.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.mongodb.client.MongoClient;
import com.mongodb.connection.ClusterConnectionMode;
import com.mongodb.connection.ClusterDescription;
import com.mongodb.connection.ClusterType;
import io.github.jdubois.bootui.engine.mongodb.MongoDbSettings;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InjectableBean;
import io.quarkus.arc.InjectableContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuarkusMongoDbProviderTest {
    @Test
    @SuppressWarnings("unchecked")
    void looksUpOnlyExistingContextualInstanceWithoutACreationalContext() {
        ArcContainer container = mock(ArcContainer.class);
        InjectableBean<Object> bean = mock(InjectableBean.class);
        InjectableContext context = mock(InjectableContext.class);
        MongoClient client = mock(com.mongodb.client.internal.MongoClientImpl.class);
        when(container.bean("sync")).thenReturn(bean);
        when(bean.isActive()).thenReturn(true);
        when(bean.getScope()).thenAnswer(invocation -> ApplicationScoped.class);
        when(container.getActiveContext(ApplicationScoped.class)).thenReturn(context);
        when(context.get(bean)).thenReturn(client);
        when(client.getClusterDescription())
                .thenReturn(new ClusterDescription(ClusterConnectionMode.SINGLE, ClusterType.UNKNOWN, List.of()));
        var snapshot = new MongoDbClientsSnapshot();
        snapshot.install(
                List.of(
                        new MongoDbClientsSnapshot.Declaration("sync", "default", "SYNC", false),
                        new MongoDbClientsSnapshot.Declaration("removed", "other", "REACTIVE", true)),
                false);
        var config = new io.smallrye.config.SmallRyeConfigBuilder().build();
        var provider = new QuarkusMongoDbProvider(
                snapshot, new MongoDbClientDeclarations(config, MongoDbSettings.defaults()), container);
        var report = provider.discover(16);
        assertThat(report.clients().get(0).lifecycle()).isEqualTo("INITIALIZED");
        assertThat(report.clients().get(0).identity()).isSameAs(client);
        assertThat(report.clients().get(1).access()).isNull();
        verify(context).get(bean);
        verifyNoMoreInteractions(context);
        verify(client).getClusterDescription();
        verifyNoMoreInteractions(client);
        verify(container, never()).bean("removed");
    }

    @Test
    @SuppressWarnings("unchecked")
    void inactiveBeanNeverResolvesAContextOrClient() {
        ArcContainer container = mock(ArcContainer.class);
        InjectableBean<Object> bean = mock(InjectableBean.class);
        when(container.bean("inactive")).thenReturn(bean);
        when(bean.isActive()).thenReturn(false);
        var snapshot = new MongoDbClientsSnapshot();
        snapshot.install(List.of(new MongoDbClientsSnapshot.Declaration("inactive", "default", "SYNC", false)), false);
        var provider = new QuarkusMongoDbProvider(
                snapshot,
                new MongoDbClientDeclarations(
                        new io.smallrye.config.SmallRyeConfigBuilder().build(), MongoDbSettings.defaults()),
                container);
        assertThat(provider.discover(16).clients().get(0).lifecycle()).isEqualTo("INACTIVE");
        verify(container, never()).getActiveContext(any());
    }
}
