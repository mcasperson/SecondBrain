package secondbrain.infrastructure.mock;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;
import secondbrain.domain.context.RagMultiDocumentContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(MockLLmCLient.class)
class MockLLmCLientTest {

    @Inject
    private MockLLmCLient client;

    @Test
    void callReturnsEmptyStringWhenNoResponseIsSet() {
        assertEquals("", client.call("prompt", Map.of()));
        assertEquals("", client.call("prompt", "model", Map.of()));
    }

    @Test
    void callReturnsTheConfiguredResponse() {
        client.setMockResponse("mocked answer");

        assertEquals("mocked answer", client.call("prompt", Map.of()));
    }

    @Test
    void callWithModelReturnsTheConfiguredResponse() {
        client.setMockResponse("mocked answer");

        assertEquals("mocked answer", client.call("prompt", "model", Map.of("key", "value")));
    }

    @Test
    void settingNullResponseRestoresTheEmptyDefault() {
        client.setMockResponse("mocked answer");
        client.setMockResponse(null);

        assertEquals("", client.call("prompt", Map.of()));
        assertEquals("", client.call("prompt", "model", Map.of()));
    }

    @Test
    void callWithCacheReturnsOneResponsePerPrompt() {
        client.setMockResponse("mocked answer");
        final RagMultiDocumentContext<Void> ragDoc = new RagMultiDocumentContext<>(List.of("first", "second", "third"));

        final RagMultiDocumentContext<Void> result = client.callWithCache(ragDoc, Map.of(), "tool");

        assertEquals(List.of("mocked answer", "mocked answer", "mocked answer"), result.getResponses());
        assertEquals(List.of("first", "second", "third"), result.getPrompts());
        assertEquals(List.of(), ragDoc.getResponses());
    }

    @Test
    void callWithCacheUsesEmptyResponsesWhenNoResponseIsSet() {
        final RagMultiDocumentContext<Void> ragDoc = new RagMultiDocumentContext<>(List.of("first", "second"));

        final RagMultiDocumentContext<Void> result = client.callWithCache(ragDoc, Map.of(), "tool");

        assertEquals(List.of("", ""), result.getResponses());
    }

    @Test
    void callWithCacheWithoutPromptsReturnsNoResponses() {
        client.setMockResponse("mocked answer");
        final RagMultiDocumentContext<Void> ragDoc = new RagMultiDocumentContext<>(List.of());

        final RagMultiDocumentContext<Void> result = client.callWithCache(ragDoc, Map.of(), "tool");

        assertEquals(List.of(), result.getResponses());
        assertNotNull(result.responses());
    }
}
