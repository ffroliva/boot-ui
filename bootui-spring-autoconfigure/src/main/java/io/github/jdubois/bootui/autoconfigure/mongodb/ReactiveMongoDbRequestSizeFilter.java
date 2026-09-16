package io.github.jdubois.bootui.autoconfigure.mongodb;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.reactive.AbstractReactiveBootUiFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** WebFlux's action-specific bound; no scheduler and no servlet linkage. */
public final class ReactiveMongoDbRequestSizeFilter extends AbstractReactiveBootUiFilter implements Ordered {
    private static final int MAX_BYTES = 4096;

    public ReactiveMongoDbRequestSizeFilter(BootUiProperties properties) {
        super(properties);
    }

    @Override
    public int getOrder() {
        return Integer.MIN_VALUE + 4;
    }

    @Override
    protected boolean shouldNotFilter(ServerWebExchange exchange) {
        return !"POST".equals(exchange.getRequest().getMethod().name())
                || !pathWithinApplication(exchange.getRequest()).equals(properties.getApiPath() + "/mongodb/inspect");
    }

    @Override
    protected Mono<Void> doFilterInternal(ServerWebExchange exchange, WebFilterChain chain) {
        if (exchange.getRequest().getHeaders().getContentLength() > MAX_BYTES) return reject(exchange);
        return DataBufferUtils.join(exchange.getRequest().getBody(), MAX_BYTES)
                .map(buffer -> {
                    try {
                        byte[] body = new byte[buffer.readableByteCount()];
                        buffer.read(body);
                        return body;
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .defaultIfEmpty(new byte[0])
                .flatMap(body -> chain.filter(exchange.mutate()
                        .request(new ServerHttpRequestDecorator(exchange.getRequest()) {
                            @Override
                            public Flux<DataBuffer> getBody() {
                                return Flux.defer(() -> Flux.just(
                                        exchange.getResponse().bufferFactory().wrap(body)));
                            }
                        })
                        .build()))
                .onErrorResume(DataBufferLimitException.class, error -> reject(exchange));
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        return writeJson(
                exchange,
                HttpStatus.CONTENT_TOO_LARGE,
                "{\"error\":\"MongoDB inspection request exceeds 4096 bytes\"}");
    }
}
