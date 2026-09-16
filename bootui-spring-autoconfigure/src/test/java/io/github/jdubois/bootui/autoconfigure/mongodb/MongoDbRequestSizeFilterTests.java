package io.github.jdubois.bootui.autoconfigure.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.server.MockServerWebExchange;

class MongoDbRequestSizeFilterTests {
    @Test
    void servletBoundsOnlyTheActionIncludingCustomPath() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.setApiPath("/internal/console");
        var filter = new MongoDbRequestSizeFilter(properties);
        for (String path : new String[] {"/internal/console/mongodb/inspect", "/internal/console/mongodb"}) {
            var request = new MockHttpServletRequest("POST", path);
            request.setContent(new byte[4097]);
            var response = new MockHttpServletResponse();
            AtomicBoolean invoked = new AtomicBoolean();
            filter.doFilter(request, response, (input, output) -> invoked.set(true));
            assertThat(response.getStatus()).isEqualTo(path.endsWith("/inspect") ? 413 : 200);
            assertThat(invoked.get()).isEqualTo(!path.endsWith("/inspect"));
        }
    }

    @Test
    void servletRetainsTheExactSmallBodyForTheJsonDecoder() throws Exception {
        var request = new MockHttpServletRequest("POST", "/bootui/api/mongodb/inspect");
        request.setContent("{\"clientId\":\"client\"}".getBytes(StandardCharsets.UTF_8));
        new MongoDbRequestSizeFilter(new BootUiProperties())
                .doFilter(
                        request,
                        new MockHttpServletResponse(),
                        (input, output) -> assertThat(
                                        new String(input.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                                .isEqualTo("{\"clientId\":\"client\"}"));
    }

    @Test
    void reactiveBoundAppliesToUnadvertisedChunkedBodiesAndPreservesSmallOnes() {
        var filter = new ReactiveMongoDbRequestSizeFilter(new BootUiProperties());
        for (int size : new int[] {20, 4097}) {
            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/bootui/api/mongodb/inspect").body("x".repeat(size)));
            AtomicBoolean invoked = new AtomicBoolean();
            filter.filter(exchange, downstream -> {
                        invoked.set(true);
                        return DataBufferUtils.join(downstream.getRequest().getBody())
                                .doOnNext(buffer -> {
                                    assertThat(buffer.readableByteCount()).isEqualTo(size);
                                    DataBufferUtils.release(buffer);
                                })
                                .then();
                    })
                    .block();
            assertThat(invoked.get()).isEqualTo(size <= 4096);
            if (size > 4096) assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        }
    }
}
