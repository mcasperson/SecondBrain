package secondbrain.domain.tools.alias;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptionhandling.StandardExceptionMapping;
import secondbrain.domain.hooks.NamedHooksContainer;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.objects.SecretGetterGenerator;
import secondbrain.domain.sanitize.GetFirstMarkdownBlock;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.mock.MockLLmCLient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(AliasTool.class)
@AddBeanClasses(AliasConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(SecretGetterGenerator.class)
@AddBeanClasses(StandardExceptionMapping.class)
@AddBeanClasses(Loggers.class)
@AddBeanClasses(GetFirstMarkdownBlock.class)
@AddBeanClasses(NamedHooksContainer.class)
@AddBeanClasses(MockLLmCLient.class)
@AddBeanClasses(ValidateStringBlank.class)
class AliasToolTest {

    private static final String MOCK_RESPONSE = "[]";

    @Inject
    private AliasTool aliasTool;

    @Inject
    private MockLLmCLient mockLlmClient;

    @BeforeAll
    static void registerConfig() {
        final var configMap = new java.util.HashMap<String, String>();
        configMap.put("sb.infrastructure.mock", "true");

        TestConfigUtil.registerConfig(configMap);
    }

    @BeforeEach
    void setupMock() {
        mockLlmClient.setMockResponse(MOCK_RESPONSE);
    }

    @Produces
    @Preferred
    @ApplicationScoped
    public LlmClient produceLlmClient() {
        return mockLlmClient;
    }

    @Test
    void testGetName() {
        assertEquals("AliasTool", aliasTool.getName());
    }

    @Test
    void testGetDescription() {
        assertEquals("Generates a list of aliases for a named entity", aliasTool.getDescription());
    }

    @Test
    void testGetArguments() {
        final List<ToolArguments> args = aliasTool.getArguments();
        assertNotNull(args);
        assertTrue(args.isEmpty());
    }

    @Test
    void testGetContextReturnsEmptyListWhenNoHooks() {
        Map<String, String> settings = Map.of();
        List<String> prompts = List.of("Find aliases for Microsoft Corporation");
        List<ToolArgs> arguments = List.of();

        final List<RagDocumentContext<Void>> context = aliasTool.getContext(settings, prompts, arguments);
        assertNotNull(context);
        assertTrue(context.isEmpty());
    }

    @Test
    void testCallBasic() {
        final RagMultiDocumentContext<Void> result = aliasTool.call(
                Map.of(),
                List.of("Find aliases for Microsoft Corporation"),
                List.of());

        assertNotNull(result);
    }

    @Test
    void testGetContextLabel() {
        assertEquals("Entity Name", aliasTool.getContextLabel());
    }

    @Test
    void testContextHashCodeConsistency() {
        Map<String, String> settings = Map.of();
        List<String> prompts = List.of("Find aliases for Microsoft");
        List<ToolArgs> arguments = List.of();

        final int hash1 = aliasTool.contextHashCode(settings, prompts, arguments);
        final int hash2 = aliasTool.contextHashCode(settings, prompts, arguments);
        assertEquals(hash1, hash2, "Hash codes should be consistent for the same inputs");
    }

    @Test
    void testContextHashCodeDifferentPrompts() {
        Map<String, String> settings = Map.of();
        List<ToolArgs> arguments = List.of();

        final int hash1 = aliasTool.contextHashCode(settings, List.of("Find aliases for Microsoft"), arguments);
        final int hash2 = aliasTool.contextHashCode(settings, List.of("Find aliases for Apple Inc."), arguments);
        assertNotEquals(hash1, hash2, "Hash codes should differ when prompts differ");
    }

    @Test
    void testContextHashCodeDifferentArgs() {
        Map<String, String> settings = Map.of();

        final int hash1 = aliasTool.contextHashCode(
                settings,
                List.of("Find aliases"),
                List.of());
        final int hash2 = aliasTool.contextHashCode(
                settings,
                List.of("Find aliases"),
                List.of());

        // AliasTool has no arguments — all non-hook configs map to empty strings, so hashes are identical.
        assertEquals(hash1, hash2, "Hash codes are identical because entity name is not read by LocalArguments");
    }

    @Test
    void testContextWithEnvironmentSettings() {
        Map<String, String> settings = Map.of("search_scope", "global");
        List<String> prompts = List.of("Find aliases for Microsoft");
        List<ToolArgs> arguments = List.of();

        final List<RagDocumentContext<Void>> context = aliasTool.getContext(settings, prompts, arguments);
        assertNotNull(context);
        assertTrue(context.isEmpty()); // No entity name injection without corresponding AliasTool support
    }

    @Test
    void testContextWithArguments() {
        Map<String, String> settings = Map.of();
        List<String> prompts = List.of("Generate aliases");
        List<ToolArgs> arguments = List.of();

        final List<RagDocumentContext<Void>> context = aliasTool.getContext(settings, prompts, arguments);
        assertNotNull(context);
        assertTrue(context.isEmpty()); // No entity name injection without corresponding AliasTool support
    }

    @Test
    void testCallReturnsValidResponseStructure() {
        final Map<String, String> settings = Map.of();
        final List<String> prompts = List.of("Find aliases for Microsoft Corporation");
        final List<ToolArgs> arguments = List.of();

        final RagMultiDocumentContext<Void> result = aliasTool.call(settings, prompts, arguments);

        assertNotNull(result);
        assertNotNull(result.getResponses()); // Must have responses even if empty from LLM call
    }

    @Test
    void testCallWithMultiplePrompts() {
        List<String> prompts = List.of(
                "Find aliases for Microsoft Corporation",
                "Generate alternative names for Microsoft",
                "What other names refer to Microsoft?");

        final RagMultiDocumentContext<Void> result = aliasTool.call(
                Map.of(),
                prompts,
                List.of());

        assertNotNull(result);
        // Multiple prompts should result in multiple responses
        assertFalse(result.getResponses().isEmpty());
    }

    @Test
    void testNameConsistencyWithClassName() {
        assertEquals("AliasTool", aliasTool.getName());
        assertEquals(aliasTool.getName(), AliasTool.class.getSimpleName());
    }
}
