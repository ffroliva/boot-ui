package io.github.jdubois.bootui.webfluxsample;

import io.github.jdubois.bootui.conformance.AbstractMcpConformanceTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        classes = BootUiWebfluxSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/mcp-conformance/application-bootui.properties",
            "bootui.panels.copilot.enabled=false",
            "bootui.panels.heap-dump.read-only=true",
            "bootui.heap-dump.capture-enabled=false",
            "bootui.claude-code.enabled=OFF",
            "bootui.mcp.max-payload-bytes=256"
        })
class WebFluxMcpConformanceTest extends AbstractMcpConformanceTest {

    @LocalServerPort
    int port;

    @Override
    protected String baseUrl() {
        return "http://localhost:" + port;
    }
}
