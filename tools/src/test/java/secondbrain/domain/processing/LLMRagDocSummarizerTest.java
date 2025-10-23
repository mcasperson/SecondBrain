package secondbrain.domain.processing;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import secondbrain.domain.config.LocalConfigSummarizer;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.data.IdData;
import secondbrain.domain.encryption.JasyptEncryptor;
import secondbrain.domain.injection.Preferred;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.mock.MockLLmCLient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(LLMRagDocSummarizer.class)
@AddBeanClasses(MockLLmCLient.class)
@AddBeanClasses(JasyptEncryptor.class)
class LLMRagDocSummarizerTest {
    private static final String MOCK_RESPONSE = "Mock Summary Response";

    @Inject
    private LLMRagDocSummarizer summarizer;

    private LocalConfigSummarizer config;

    private Map<String, String> environmentSettings;

    @BeforeEach
    void updateConfig() {
        final var configSource = new PropertiesConfigSource(
                Map.of(
                        "sb.encryption.password", "1234567890"
                ),
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

    @Produces
    @Preferred
    @ApplicationScoped
    public LlmClient produceLlmClient(final MockLLmCLient llmClient) {
        llmClient.setMockResponse(MOCK_RESPONSE);
        return llmClient;
    }

    @BeforeEach
    void setUp() {
        config = () -> "Summarize this document";
        environmentSettings = Map.of();
    }

    @Test
    void testGetDocumentSummary_SingleDocument() {
        RagDocumentContext<TestTask> inputDoc = createContext("doc-1", "Original document content");

        RagDocumentContext<TestTask> result = summarizer.getDocumentSummary(
                "testTool",
                "contextLabel",
                "testDatasource",
                inputDoc,
                environmentSettings,
                config
        );

        assertNotNull(result);
        assertEquals(MOCK_RESPONSE, result.document());
        assertEquals("doc-1", result.id());
        assertEquals(1, result.getIntermediateResults().size());
        assertTrue(result.getIntermediateResults().getFirst().content().contains(MOCK_RESPONSE));
    }

    @Test
    void testGetDocumentSummary_MultipleDocuments() {
        List<RagDocumentContext<TestTask>> inputDocs = List.of(
                createContext("doc-1", "First document"),
                createContext("doc-2", "Second document")
        );

        List<RagDocumentContext<TestTask>> results = summarizer.getDocumentSummary(
                "testTool",
                "contextLabel",
                "testDatasource",
                inputDocs,
                environmentSettings,
                config
        );

        assertEquals(2, results.size());
        assertEquals(MOCK_RESPONSE, results.get(0).document());
        assertEquals(MOCK_RESPONSE, results.get(1).document());
        assertEquals("doc-1", results.get(0).id());
        assertEquals("doc-2", results.get(1).id());

    }

    @Test
    void testGetDocumentSummary_IntermediateResultContainsPrompt() {
        RagDocumentContext<TestTask> inputDoc = createContext("doc-1", "Test content");


        RagDocumentContext<TestTask> result = summarizer.getDocumentSummary(
                "testTool",
                "contextLabel",
                "testDatasource",
                inputDoc,
                environmentSettings,
                config
        );

        String intermediateResult = result.getIntermediateResults().getFirst().content();
        assertTrue(intermediateResult.contains("Prompt: Summarize this document"));
        assertTrue(intermediateResult.contains(MOCK_RESPONSE));
    }

    private RagDocumentContext<TestTask> createContext(String id, String document) {
        return new RagDocumentContext<>(
                "toolName",
                "contextLabel",
                document,
                List.of(),
                id,
                new TestTask(id),
                null,
                null,
                null,
                null
        );
    }

    private record TestTask(String id) implements IdData {
        @Override
        public String getId() {
            return id;
        }
    }
}
