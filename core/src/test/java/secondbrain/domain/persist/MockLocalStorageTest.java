package secondbrain.domain.persist;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(MockLocalStorage.class)
// Generators may return null values
@SuppressWarnings("NullAway")
class MockLocalStorageTest {
    private final AtomicInteger generated = new AtomicInteger();

    @Inject
    private MockLocalStorage storage;

    private <T> GenerateValue<T> counting(final T value) {
        return () -> {
            generated.incrementAndGet();
            return value;
        };
    }

    private static <T> void assertGenerated(final T expected, final CacheResult<T> result) {
        assertEquals(expected, result.result());
        assertNull(result.exception());
        assertFalse(result.fromCache());
    }

    @Test
    void getStringAlwaysMisses() {
        assertEquals(new CacheResult<String>(null, null, false), storage.getString("tool", "source", "hash"));
    }

    @Test
    void putStringDoesNotStoreTheValue() {
        storage.putString("tool", "source", "hash", 100L, "value");
        storage.putString("tool", "source", "hash", "value");
        storage.flush();

        assertNull(storage.getString("tool", "source", "hash").result());
        assertGenerated("generated", storage.getOrPutString("tool", "source", "hash", counting("generated")));
    }

    @Test
    void getOrPutStringGeneratesTheValueEveryTime() {
        assertGenerated("value", storage.getOrPutString("tool", "source", "hash", 60L, counting("value")));
        assertGenerated("value", storage.getOrPutString("tool", "source", "hash", counting("value")));

        assertEquals(2, generated.get());
    }

    @Test
    void getOrPutStringReturnsANullGeneratedValue() {
        assertGenerated(null, storage.getOrPutString("tool", "source", "hash", counting((String) null)));
    }

    @Test
    void getOrPutStringPropagatesGeneratorFailures() {
        final IllegalStateException failure = new IllegalStateException("generate failed");

        assertSame(failure, assertThrows(IllegalStateException.class, () -> storage.getOrPutString("tool", "source", "hash", () -> {
            throw failure;
        })));
    }

    @Test
    void getOrPutObjectGeneratesTheValueEveryTime() {
        assertGenerated(42, storage.getOrPutObject("tool", "source", "hash", 60L, Integer.class, counting(42)));
        assertGenerated(42, storage.getOrPutObject("tool", "source", "hash", Integer.class, counting(42)));

        assertEquals(2, generated.get());
    }

    @Test
    void getOrPutListGeneratesTheValueEveryTime() {
        assertGenerated(List.of("a", "b"), storage.getOrPutList("tool", "source", "hash", 60L, String.class, counting(List.of("a", "b"))));
        assertGenerated(List.of("c"), storage.getOrPutList("tool", "source", "hash", String.class, counting(List.of("c"))));

        assertEquals(2, generated.get());
    }

    @Test
    void getOrPutObjectArrayGeneratesTheValue() {
        final String[] value = {"a", "b"};

        final CacheResult<String[]> result = storage.getOrPutObjectArray("tool", "source", "hash", 60L, String.class, String[].class, counting(value));

        assertSame(value, result.result());
        assertNull(result.exception());
        assertFalse(result.fromCache());
        assertEquals(1, generated.get());
    }

    @Test
    void persistArrayResultGeneratesTheValue() {
        final Integer[] value = {1, 2, 3};

        final CacheResult<Integer[]> result = storage.persistArrayResult("tool", "source", "hash", 60L, counting(value));

        assertSame(value, result.result());
        assertNull(result.exception());
        assertFalse(result.fromCache());
        assertEquals(1, generated.get());
    }

    @Test
    void getOrPutGenericWithOneContainedTypeGeneratesTheValueEveryTime() {
        assertGenerated(List.of(1), storage.getOrPutGeneric("tool", "source", "hash", 60L, List.class, Integer.class, counting(List.of(1))));
        assertGenerated(List.of(2), storage.getOrPutGeneric("tool", "source", "hash", List.class, Integer.class, counting(List.of(2))));

        assertEquals(2, generated.get());
    }

    @Test
    void getOrPutGenericWithTwoContainedTypesGeneratesTheValueEveryTime() {
        assertGenerated(Map.of("a", 1), storage.getOrPutGeneric("tool", "source", "hash", 60L, Map.class, String.class, Integer.class, counting(Map.of("a", 1))));
        assertGenerated(Map.of("b", 2), storage.getOrPutGeneric("tool", "source", "hash", Map.class, String.class, Integer.class, counting(Map.of("b", 2))));

        assertEquals(2, generated.get());
    }
}
