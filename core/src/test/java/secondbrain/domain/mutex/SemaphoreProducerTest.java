package secondbrain.domain.mutex;

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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SemaphoreProducer}. The file and Cosmos semaphores are replaced with Mockito mocks that each
 * return their own name instead of running the callback, so the selected semaphore can be identified through the
 * produced {@link Preferred} bean without creating lock files or connecting to Cosmos. The config is registered before
 * the produced bean is first used, which is when the producer reads sb.mutex.provider.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(SemaphoreProducer.class)
class SemaphoreProducerTest {
	@Inject
	@Preferred
	private Semaphore semaphore;

	private static <T extends Semaphore> T namedSemaphore(final Class<T> clazz, final String name) {
		final T namedSemaphore = mock(clazz);
		when(namedSemaphore.<Object>acquire(anyInt(), anyString(), any())).thenReturn(name);
		return namedSemaphore;
	}

	@Produces
	@ExcludeBean
	FileLockSemaphore produceFileLockSemaphore() {
		return namedSemaphore(FileLockSemaphore.class, "file");
	}

	@Produces
	@ExcludeBean
	CosmosSemaphore produceCosmosSemaphore() {
		return namedSemaphore(CosmosSemaphore.class, "cosmos");
	}

	private String selectedSemaphore(final Map<String, String> config) {
		TestConfigUtil.registerConfig(config);
		return semaphore.acquire(2, "lock", () -> "callback was run");
	}

	@Test
	void defaultsToTheFileSemaphoreWhenNoProviderIsConfigured() {
		assertEquals("file", selectedSemaphore(Map.of()));
	}

	@ParameterizedTest
	@ValueSource(strings = {"cosmos", "COSMOS", "Cosmos"})
	void selectsTheCosmosSemaphoreIgnoringCase(final String provider) {
		assertEquals("cosmos", selectedSemaphore(Map.of("sb.mutex.provider", provider)));
	}

	@ParameterizedTest
	@ValueSource(strings = {"file", "FILE", "h2", "cosmosdb", " cosmos ", "mock"})
	void anyOtherProviderSelectsTheFileSemaphore(final String provider) {
		assertEquals("file", selectedSemaphore(Map.of("sb.mutex.provider", provider)));
	}

	@Test
	void theProducedSemaphoreIsCreatedOnceForTheApplication() {
		assertEquals("cosmos", selectedSemaphore(Map.of("sb.mutex.provider", "cosmos")));
		assertEquals("cosmos", selectedSemaphore(Map.of("sb.mutex.provider", "file")));
	}
}
