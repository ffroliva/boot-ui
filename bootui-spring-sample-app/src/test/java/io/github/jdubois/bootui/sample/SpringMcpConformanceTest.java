package io.github.jdubois.bootui.sample;

import io.github.jdubois.bootui.conformance.AbstractMcpConformanceTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/mcp-conformance/application-bootui.properties",
            "bootui.panels.copilot.enabled=false",
            "bootui.panels.heap-dump.read-only=true",
            "bootui.heap-dump.capture-enabled=false",
            "bootui.claude-code.enabled=OFF",
            "bootui.mcp.max-payload-bytes=256"
        })
class SpringMcpConformanceTest extends AbstractMcpConformanceTest {

    @LocalServerPort
    int port;

    @Override
    protected String baseUrl() {
        return "http://localhost:" + port;
    }
}
