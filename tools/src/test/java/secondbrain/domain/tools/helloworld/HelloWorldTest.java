package secondbrain.domain.tools.helloworld;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptionhandling.StandardExceptionMapping;
import secondbrain.domain.objects.SecretGetterGenerator;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateStringBlank;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(HelloWorld.class)
@AddBeanClasses(HelloWorldConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(SecretGetterGenerator.class)
@AddBeanClasses(StandardExceptionMapping.class)
@AddBeanClasses(ValidateStringBlank.class)
class HelloWorldTest {

    @Inject
    private HelloWorld helloWorld;

    @BeforeAll
    static void registerConfig() {
        final var configMap = new java.util.HashMap<String, String>();
        // Don't set sb.helloworld.message so tool args and context can be tested

        final var configSource = new PropertiesConfigSource(
                configMap,
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
    void testGetName() {
        assertEquals("HelloWorld", helloWorld.getName());
    }

    @Test
    void testGetDescription() {
        assertEquals("Returns a greeting message", helloWorld.getDescription());
    }

    @Test
    void testGetArguments() {
        final List<ToolArguments> args = helloWorld.getArguments();
        assertEquals(1, args.size());
        assertEquals("message", args.get(0).name());
    }

    @Test
    void testGetContextReturnsEmptyList() {
        final List<RagDocumentContext<Void>> context = helloWorld.getContext(
                Map.of(),
                List.of("Hello"),
                List.of());
        assertNotNull(context);
        assertTrue(context.isEmpty());
    }

    @Test
    void testCallWithDefaultMessage() {
        final RagMultiDocumentContext<Void> result = helloWorld.call(
                Map.of(),
                List.of("greet me"),
                List.of());

        assertNotNull(result);
        assertTrue(result.getResponse().contains("Hello, World!"));
    }

    @Test
    void testCallWithExplicitMessage() {
        final RagMultiDocumentContext<Void> result = helloWorld.call(
                Map.of(),
                List.of("greet me"),
                List.of(new ToolArgs("message", "Alice", false)));

        assertNotNull(result);
        assertTrue(result.getResponse().contains("Hello, Alice!"));
    }

    @Test
    void testCallWithContextMessage() {
        final RagMultiDocumentContext<Void> result = helloWorld.call(
                Map.of("message", "Bob"),
                List.of("greet me"),
                List.of());

        assertNotNull(result);
        assertTrue(result.getResponse().contains("Hello, Bob!"));
    }

    @Test
    void testContextHashCode() {
        final int hash1 = helloWorld.contextHashCode(Map.of(), List.of("prompt1"), List.of());
        final int hash2 = helloWorld.contextHashCode(Map.of(), List.of("prompt2"), List.of());
        assertNotEquals(hash1, hash2);
    }

    @Test
    void testGetContextLabel() {
        assertEquals("Unused", helloWorld.getContextLabel());
    }
}
