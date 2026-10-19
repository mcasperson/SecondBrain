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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MutexProducer}. The file and Cosmos mutexes are replaced with Mockito mocks that each return
 * their own name instead of running the callback, so the selected mutex can be identified through the produced
 * {@link Preferred} bean without creating lock files or connecting to Cosmos. The config is registered before the
 * produced bean is first used, which is when the producer reads sb.mutex.provider.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(MutexProducer.class)
class MutexProducerTest {
	@Inject
	@Preferred
	private Mutex mutex;

	private static <T extends Mutex> T namedMutex(final Class<T> clazz, final String name) {
		final T namedMutex = mock(clazz);
		when(namedMutex.<Object>acquire(anyString(), any())).thenReturn(name);
		return namedMutex;
	}

	@Produces
	@ExcludeBean
	FileLockMutex produceFileLockMutex() {
		return namedMutex(FileLockMutex.class, "file");
	}

	@Produces
	@ExcludeBean
	CosmosMutex produceCosmosMutex() {
		return namedMutex(CosmosMutex.class, "cosmos");
	}

	private String selectedMutex(final Map<String, String> config) {
		TestConfigUtil.registerConfig(config);
		return mutex.acquire("lock", () -> "callback was run");
	}

	@Test
	void defaultsToTheFileMutexWhenNoProviderIsConfigured() {
		assertEquals("file", selectedMutex(Map.of()));
	}

	@ParameterizedTest
	@ValueSource(strings = {"cosmos", "COSMOS", "Cosmos"})
	void selectsTheCosmosMutexIgnoringCase(final String provider) {
		assertEquals("cosmos", selectedMutex(Map.of("sb.mutex.provider", provider)));
	}

	@ParameterizedTest
	@ValueSource(strings = {"file", "FILE", "h2", "cosmosdb", " cosmos ", "mock"})
	void anyOtherProviderSelectsTheFileMutex(final String provider) {
		assertEquals("file", selectedMutex(Map.of("sb.mutex.provider", provider)));
	}

	@Test
	void theProducedMutexIsCreatedOnceForTheApplication() {
		assertEquals("cosmos", selectedMutex(Map.of("sb.mutex.provider", "cosmos")));
		assertEquals("cosmos", selectedMutex(Map.of("sb.mutex.provider", "file")));
	}
}
