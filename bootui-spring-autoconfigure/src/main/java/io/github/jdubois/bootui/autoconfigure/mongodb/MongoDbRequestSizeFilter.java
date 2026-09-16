package io.github.jdubois.bootui.autoconfigure.mongodb;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.web.AbstractBootUiFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** A small, action-specific bound before JSON decoding; runs after the existing access filters. */
public final class MongoDbRequestSizeFilter extends AbstractBootUiFilter {
    static final int MAX_BYTES = 4096;

    public MongoDbRequestSizeFilter(BootUiProperties properties) {
        super(properties);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !pathWithinApplication(request).equals(properties.getApiPath() + "/mongodb/inspect");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BYTES) {
            reject(response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) {
            reject(response);
            return;
        }
        ByteArrayInputStream input = new ByteArrayInputStream(body);
        ServletInputStream bounded = new ServletInputStream() {
            @Override
            public int read() {
                return input.read();
            }

            @Override
            public int read(byte[] bytes, int offset, int length) {
                return input.read(bytes, offset, length);
            }

            @Override
            public boolean isFinished() {
                return input.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new IllegalStateException("MongoDB action input uses blocking request decoding");
            }
        };
        chain.doFilter(
                new HttpServletRequestWrapper(request) {
                    @Override
                    public ServletInputStream getInputStream() {
                        return bounded;
                    }

                    @Override
                    public BufferedReader getReader() {
                        return new BufferedReader(new InputStreamReader(bounded, StandardCharsets.UTF_8));
                    }
                },
                response);
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"MongoDB inspection request exceeds 4096 bytes\"}");
    }
}
