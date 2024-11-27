package secondbrain.domain.hanlder;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import secondbrain.domain.json.JsonDeserializerJackson;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.response.OkResponseValidation;
import secondbrain.domain.toolbuilder.ToolBuilderLlama3;
import secondbrain.domain.toolbuilder.ToolSelector;
import secondbrain.domain.tooldefs.ToolCall;
import secondbrain.domain.tools.smoketest.SmokeTest;
import secondbrain.domain.validate.ValidateListEmptyOrNull;
import secondbrain.infrastructure.ollama.OllamaClient;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(Loggers.class)
@AddBeanClasses(OkResponseValidation.class)
@AddBeanClasses(ToolBuilderLlama3.class)
@AddBeanClasses(JsonDeserializerJackson.class)
@AddBeanClasses(ValidateListEmptyOrNull.class)
@AddBeanClasses(SmokeTest.class)
@AddBeanClasses(OllamaClient.class)
public class ToolSelectionTest {

    @Container
    public GenericContainer<?> ollamaContainer = new GenericContainer<>("ollama/ollama:latest")
            .withFileSystemBind(Paths.get(System.getProperty("java.io.tmpdir")).resolve(Paths.get("secondbrain")).toString(), "/root/.ollama")
            .withExposedPorts(11434);

    @Inject
    ToolSelector toolSelector;

    /**
     * https://github.com/weld/weld-testing/issues/81#issuecomment-1564002983
     */
    @BeforeEach
    void updateConfig() {
        final var configSource = new PropertiesConfigSource(
                Map.of("sb.ollama.url", "http://localhost:" + ollamaContainer.getMappedPort(11434)),
                "TestConfig",
                Integer.MAX_VALUE
        );
        final Config newConfig = new SmallRyeConfigBuilder()
                .withSources(configSource)
                .build();

        final var configProviderResolver = ConfigProviderResolver.instance();
        final var oldConfig = configProviderResolver.getConfig();

        configProviderResolver.releaseConfig(oldConfig);
        configProviderResolver.registerConfig(
                newConfig,
                Thread.currentThread().getContextClassLoader()
        );
    }

    @Test
        //@Disabled
    void testOllamaContainerIsRunning() throws IOException, InterruptedException {
        ollamaContainer.start();
        ollamaContainer.execInContainer("/usr/bin/ollama", "pull", "llama3.2");
        ollamaContainer.execInContainer("/usr/bin/ollama", "pull", "llama3.1");
        assertTrue(ollamaContainer.isRunning());

        final ToolCall tool = toolSelector.getTool("Perform a smoke test");
        assertEquals("SmokeTest", tool.toolDefinition().toolName());
    }
}