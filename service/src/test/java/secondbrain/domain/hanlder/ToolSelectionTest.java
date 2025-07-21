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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.RepeatedTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.context.CosineSimilarityCalculator;
import secondbrain.domain.context.JdlSentenceVectorizer;
import secondbrain.domain.context.SimpleSentenceSplitter;
import secondbrain.domain.debug.DebugToolArgsKeyValue;
import secondbrain.domain.encryption.JasyptEncryptor;
import secondbrain.domain.exceptionhandling.LoggingExceptionHandler;
import secondbrain.domain.json.JsonDeserializerJackson;
import secondbrain.domain.limit.DocumentTrimmerExactKeywords;
import secondbrain.domain.limit.ListLimiterAtomicCutOff;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.persist.H2LocalStorage;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.response.OkResponseValidation;
import secondbrain.domain.sanitize.RemoveSpacing;
import secondbrain.domain.sanitize.SanitizeEmail;
import secondbrain.domain.toolbuilder.ToolBuilderLlama3;
import secondbrain.domain.toolbuilder.ToolSelector;
import secondbrain.domain.tooldefs.ToolCall;
import secondbrain.domain.tools.smoketest.SmokeTest;
import secondbrain.domain.tools.zendesk.SanitizeOrganization;
import secondbrain.domain.tools.zendesk.ZenDeskOrganization;
import secondbrain.domain.validate.Llama32ValidateInputs;
import secondbrain.domain.validate.ValidateListEmptyOrNull;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.infrastructure.github.GitHubClientProducer;
import secondbrain.infrastructure.gong.GongClientProducer;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.planhat.PlanHatClientProducer;
import secondbrain.infrastructure.slack.SlackClientProducer;
import secondbrain.infrastructure.zendesk.ZenDeskClientProducer;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * We have a couple of challenges to deal with when testing LLMs.
 * <p>
 * First, we need a test instance of Ollama. Because LLMs are large file, we want to have a persistent directory where
 * these files will be downloaded into. Testcontainers provides a useful solution here to spin up Ollama, download files,
 * save them in a shared location, and expose a random port for the test to connect to.
 * <p>
 * The second issue is that we need to inject CDI beans into the test classes. Weld Junit 5 takes care of this for us.
 * <p>
 * We then need to inject Microprofile configuration values. This is documented
 * <a href="https://github.com/weld/weld-testing/issues/81#issuecomment-1564002983">here</a>.
 * <p>
 * Finally, we need to accept that testing LLMs means we can't assume a 100% success rate. Standard retry logic is not
 * sufficient as increasing the number of retries essentially increases the acceptable failure rate i.e. retying 3 times
 * means we accept a success rate of 33%. We need to implement some custom logic to test that our logic has some minimum
 * success rate, sate 80%.
 */
@Disabled
@Testcontainers
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(H2LocalStorage.class)
@AddBeanClasses(Loggers.class)
@AddBeanClasses(JasyptEncryptor.class)
@AddBeanClasses(OkResponseValidation.class)
@AddBeanClasses(ToolBuilderLlama3.class)
@AddBeanClasses(JsonDeserializerJackson.class)
@AddBeanClasses(ValidateListEmptyOrNull.class)
@AddBeanClasses(SmokeTest.class)
@AddBeanClasses(ZenDeskOrganization.class)
@AddBeanClasses(OllamaClient.class)
@AddBeanClasses(DebugToolArgsKeyValue.class)
@AddBeanClasses(ListLimiterAtomicCutOff.class)
@AddBeanClasses(SimpleSentenceSplitter.class)
@AddBeanClasses(JdlSentenceVectorizer.class)
@AddBeanClasses(CosineSimilarityCalculator.class)
@AddBeanClasses(PromptBuilderSelector.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(SanitizeOrganization.class)
@AddBeanClasses(Llama32ValidateInputs.class)
@AddBeanClasses(RemoveSpacing.class)
@AddBeanClasses(SanitizeEmail.class)
@AddBeanClasses(LoggingExceptionHandler.class)
@AddBeanClasses(DocumentTrimmerExactKeywords.class)
@AddBeanClasses(ZenDeskClientProducer.class)
@AddBeanClasses(SlackClientProducer.class)
@AddBeanClasses(PlanHatClientProducer.class)
@AddBeanClasses(GongClientProducer.class)
@AddBeanClasses(GitHubClientProducer.class)
public class ToolSelectionTest {

    final @Container
    public GenericContainer<?> ollamaContainer = new GenericContainer<>("ollama/ollama:latest")
            // Mount a fixed directory where models can be downloaded and reused
            .withFileSystemBind(Paths.get(System.getProperty("user.home")).resolve(Paths.get(".secondbrain", "test")).toString(), "/root/.ollama")
            .withExposedPorts(11434);

    private final AtomicInteger testToolSelectionCounter = new AtomicInteger(0);
    private final AtomicInteger testZendDeskToolCounter = new AtomicInteger(0);

    @Inject
    ToolSelector toolSelector;

    /**
     * <a href="https://github.com/weld/weld-testing/issues/81#issuecomment-1564002983">...</a>
     */
    @BeforeEach
    void updateConfig() {
        final var configSource = new PropertiesConfigSource(
                Map.of(
                        "sb.ollama.url", "http://localhost:" + ollamaContainer.getMappedPort(11434),
                        // Unfortunately llama3.2 is not reliable enough for tool selection.
                        // To make these tests reliable, we need to use llama3.1, which is a larger model.
                        "sb.ollama.toolmodel", "llama3.1:8b-instruct-q5_K_M "),
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

    @BeforeEach
    void getModels() throws IOException, InterruptedException {
        ollamaContainer.start();
        ollamaContainer.execInContainer("/usr/bin/ollama", "pull", "llama3.2");
        ollamaContainer.execInContainer("/usr/bin/ollama", "pull", "llama3.1:8b-instruct-q5_K_M ");
    }

    @RepeatedTest(value = 5, failureThreshold = 1)
    void testToolSelection() {
        try {
            final ToolCall tool = toolSelector.getTool("Perform a smoke test", Map.of());
            assertEquals("SmokeTest", tool.toolDefinition().toolName());
        } catch (Exception e) {
            // Allow up o one failure, or an 20% failure rate
            if (testToolSelectionCounter.incrementAndGet() > 1) {
                throw e;
            }
        }
    }

    @RepeatedTest(value = 5, failureThreshold = 1)
    void testZendDeskTool() {
        try {
            final ToolCall tool = toolSelector.getTool("Given 8 hours worth of ZenDesk tickets, with up to 10 comments, provide a summary of the questions and problems in the style of a news article with up to 3 paragraphs.", Map.of());
            assertEquals("ZenDeskOrganization", tool.toolDefinition().toolName());
            assertEquals("10", tool.toolDefinition().toolArgs().stream().filter(arg -> arg.argName().equals("numComments")).findFirst().get().argValue());
            assertEquals("8", tool.toolDefinition().toolArgs().stream().filter(arg -> arg.argName().equals("hours")).findFirst().get().argValue());
        } catch (Exception e) {
            // Allow up o one failure, or an 20% failure rate
            if (testZendDeskToolCounter.incrementAndGet() > 1) {
                throw e;
            }
        }
    }
}