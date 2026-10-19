package secondbrain.domain.persist;

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
import org.jspecify.annotations.Nullable;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.test.TestConfigUtil;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LocalStorageProducer}. The H2 and Cosmos storages are replaced with Mockito mocks that each
 * return their own name as the cached value, so the selected storage can be identified through the produced
 * {@link Preferred} bean without opening an H2 database file or connecting to Cosmos. The config is registered before
 * the produced bean is first used, which is when the producer reads sb.localstorage.provider.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(LocalStorageProducer.class)
class LocalStorageProducerTest {
	@Inject
	@Preferred
	private LocalStorage localStorage;

	private static <T extends LocalStorage> T namedStorage(final Class<T> clazz, final String name) {
		final T storage = mock(clazz);
		when(storage.getOrPutString(anyString(), anyString(), anyString(), any()))
				.thenReturn(new CacheResult<>(name, null, true));
		return storage;
	}

	@Produces
	@ExcludeBean
	H2LocalStorage produceH2LocalStorage() {
		return namedStorage(H2LocalStorage.class, "h2");
	}

	@Produces
	@ExcludeBean
	CosmosLocalStorage produceCosmosLocalStorage() {
		return namedStorage(CosmosLocalStorage.class, "cosmos");
	}

	private @Nullable String selectedStorage(final Map<String, String> config) {
		TestConfigUtil.registerConfig(config);
		return localStorage.getOrPutString("tool", "source", "hash", () -> "generated").result();
	}

	@Test
	void defaultsToH2WhenNoProviderIsConfigured() {
		assertEquals("h2", selectedStorage(Map.of()));
	}

	@ParameterizedTest
	@ValueSource(strings = {"cosmos", "COSMOS", "Cosmos"})
	void selectsCosmosIgnoringCase(final String provider) {
		assertEquals("cosmos", selectedStorage(Map.of("sb.localstorage.provider", provider)));
	}

	@ParameterizedTest
	@ValueSource(strings = {"h2", "H2", "file", "cosmosdb", " cosmos ", "mock"})
	void anyOtherProviderSelectsH2(final String provider) {
		assertEquals("h2", selectedStorage(Map.of("sb.localstorage.provider", provider)));
	}

	@Test
	void theProducedStorageIsCreatedOnceForTheApplication() {
		assertEquals("cosmos", selectedStorage(Map.of("sb.localstorage.provider", "cosmos")));
		assertEquals("cosmos", selectedStorage(Map.of("sb.localstorage.provider", "h2")));
	}
}
