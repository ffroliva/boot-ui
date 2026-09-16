package io.github.jdubois.bootui.sample.mongodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.smallrye.common.annotation.Blocking;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;

class MongoDiagnosticsProfileTest {

    @Test
    void nativeClientDependencyAndFixtureAreOptIn() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var pom = factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        var xpath = XPathFactory.newInstance().newXPath();
        assertEquals(
                "0",
                xpath.evaluate("count(/project/dependencies/dependency[artifactId='quarkus-mongodb-client'])", pom));
        assertEquals(
                "1",
                xpath.evaluate(
                        "count(/project/profiles/profile[id='mongodb-diagnostics']/dependencies/dependency"
                                + "[artifactId='quarkus-mongodb-client'])",
                        pom));
        var properties = new Properties();
        try (var input =
                Files.newInputStream(Path.of("src/main/resources/application-mongodb-diagnostics.properties"))) {
            properties.load(input);
        }
        assertEquals("${BOOTUI_SAMPLE_MONGODB_URL}", properties.getProperty("quarkus.mongodb.connection-string"));
        assertEquals("bootui_sample", properties.getProperty("quarkus.mongodb.database"));
        assertEquals("false", properties.getProperty("quarkus.mongodb.devservices.enabled"));
        assertEquals("h2", properties.getProperty("quarkus.datasource.db-kind"));
        assertEquals("false", properties.getProperty("quarkus.datasource.devservices.enabled"));
    }

    @Test
    void metadataDoesNotUseEitherClientAndBlockingWorkStaysOffTheEventLoop() throws Exception {
        assertEquals(true, new SampleMongoResource().availability().get("available"));
        assertNotNull(SampleMongoResource.class.getMethod("syncWorkload").getAnnotation(Blocking.class));
        assertFalse(SampleMongoResource.class.getMethod("reactiveWorkload").isAnnotationPresent(Blocking.class));
    }
}
