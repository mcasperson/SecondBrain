package secondbrain.domain.persist;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(MockLocalStorageReadWrite.class)
class MockLocalStorageReadWriteTest {
	@Inject
	private MockLocalStorageReadWrite storage;

	@Test
	void missingValuesAreEmpty() {
		assertEquals(Optional.empty(), storage.getString("tool", "source", "hash"));
	}

	@Test
	void putStringReturnsAndStoresTheValue() {
		assertEquals("value", storage.putString("tool", "source", "hash", 100L, "value"));

		assertEquals(Optional.of("value"), storage.getString("tool", "source", "hash"));
	}

	@Test
	void aNullTimestampIsAccepted() {
		assertEquals("value", storage.putString("tool", "source", "hash", null, "value"));

		assertEquals(Optional.of("value"), storage.getString("tool", "source", "hash"));
	}

	@Test
	void everyKeyPartSeparatesValues() {
		storage.putString("tool", "source", "hash", null, "value");

		assertEquals(Optional.empty(), storage.getString("other", "source", "hash"));
		assertEquals(Optional.empty(), storage.getString("tool", "other", "hash"));
		assertEquals(Optional.empty(), storage.getString("tool", "source", "other"));
	}

	@Test
	void puttingTheSameKeyReplacesTheValue() {
		storage.putString("tool", "source", "hash", null, "first");
		storage.putString("tool", "source", "hash", null, "second");

		assertEquals(Optional.of("second"), storage.getString("tool", "source", "hash"));
	}

	@Test
	void purgeRemovesEveryValue() {
		storage.putString("tool", "source", "hash", null, "value");
		storage.putString("tool2", "source2", "hash2", null, "value2");

		storage.purge();

		assertEquals(Optional.empty(), storage.getString("tool", "source", "hash"));
		assertEquals(Optional.empty(), storage.getString("tool2", "source2", "hash2"));
	}
}
