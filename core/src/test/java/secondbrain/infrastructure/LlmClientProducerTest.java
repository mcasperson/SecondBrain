package secondbrain.infrastructure;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.jboss.weld.junit5.auto.ExcludeBean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.infrastructure.azure.AzureClient;
import secondbrain.infrastructure.google.GoogleClient;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.ollama.OllamaClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LlmClientProducer}. The concrete LLM clients are replaced with Mockito mocks that each return
 * their own name, so the selected client can be identified through the produced {@link Preferred} bean without
 * calling a real LLM service. Weld would otherwise add the real client classes because they are parameters of the
 * producer method, so {@link ExcludeBean} replaces them with the mocks. The config is registered before the produced bean is first used, which is when the
 * producer reads sb.llm.client.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(LlmClientProducer.class)
class LlmClientProducerTest {
	@Inject
	@Preferred
	private LlmClient llmClient;

	private static <T extends LlmClient> T namedClient(final Class<T> clazz, final String name) {
		final T client = mock(clazz);
		when(client.call(any(String.class), anyMap())).thenReturn(name);
		return client;
	}

	@Produces
	@ExcludeBean
	OllamaClient produceOllamaClient() {
		return namedClient(OllamaClient.class, "ollama");
	}

	@Produces
	@ExcludeBean
	GoogleClient produceGoogleClient() {
		return namedClient(GoogleClient.class, "google");
	}

	@Produces
	@ExcludeBean
	AzureClient produceAzureClient() {
		return namedClient(AzureClient.class, "azure");
	}

	private String selectedClient(final Map<String, String> config) {
		TestConfigUtil.registerConfig(config);
		return llmClient.call("prompt", Map.of());
	}

	@Test
	void defaultsToOllamaWhenNoClientIsConfigured() {
		assertEquals("ollama", selectedClient(Map.of()));
	}

	@ParameterizedTest
	@ValueSource(strings = {"google", "GOOGLE", "Google"})
	void selectsGoogleIgnoringCase(final String client) {
		assertEquals("google", selectedClient(Map.of("sb.llm.client", client)));
	}

	@ParameterizedTest
	@ValueSource(strings = {"azure", "AZURE", "Azure"})
	void selectsAzureIgnoringCase(final String client) {
		assertEquals("azure", selectedClient(Map.of("sb.llm.client", client)));
	}

	@ParameterizedTest
	@ValueSource(strings = {"ollama", "OLLAMA", "openai", "google-gemini", " azure "})
	void anyOtherValueSelectsOllama(final String client) {
		assertEquals("ollama", selectedClient(Map.of("sb.llm.client", client)));
	}

	@Test
	void theProducedClientIsCreatedOnceForTheApplication() {
		assertEquals("azure", selectedClient(Map.of("sb.llm.client", "azure")));
		assertEquals("azure", selectedClient(Map.of("sb.llm.client", "google")));
	}
}
