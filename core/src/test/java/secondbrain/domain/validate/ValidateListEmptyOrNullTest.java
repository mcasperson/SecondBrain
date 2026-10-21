package secondbrain.domain.validate;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;
import secondbrain.domain.exceptions.EmptyList;
import secondbrain.domain.exceptions.InsufficientContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(ValidateListEmptyOrNull.class)
class ValidateListEmptyOrNullTest {

    @Inject
    private ValidateListEmptyOrNull validate;

    @Test
    void throwIfEmptyRejectsNull() {
        final EmptyList ex = assertThrows(EmptyList.class, () -> validate.throwIfEmpty(null));

        assertEquals("List is empty", ex.getMessage());
    }

    @Test
    void throwIfEmptyRejectsEmptyList() {
        final EmptyList ex = assertThrows(EmptyList.class, () -> validate.throwIfEmpty(List.of()));

        assertEquals("List is empty", ex.getMessage());
    }

    @Test
    void throwIfEmptyReturnsTheSameSingleItemList() {
        final List<String> value = List.of("a");

        assertSame(value, validate.throwIfEmpty(value));
    }

    @Test
    void throwIfEmptyWithContextRejectsNullWithoutCallingContext() {
        final List<String> calls = new ArrayList<>();
        final Function<String, String> context = item -> {
            calls.add(item);
            return item;
        };

        final EmptyList ex = assertThrows(EmptyList.class, () -> validate.throwIfEmpty(null, context));

        assertEquals("List is empty", ex.getMessage());
        assertTrue(calls.isEmpty());
    }

    @Test
    void throwIfEmptyWithContextRejectsEmptyList() {
        final EmptyList ex = assertThrows(EmptyList.class, () -> validate.throwIfEmpty(List.of(), Function.<String>identity()));

        assertEquals("List is empty", ex.getMessage());
    }

    @Test
    void throwIfEmptyWithContextRejectsListWhereEveryContextIsBlank() {
        final List<String> value = Arrays.asList("", " ", "\t", null);

        final EmptyList ex = assertThrows(EmptyList.class, () -> validate.throwIfEmpty(value, item -> item));

        assertEquals("List has empty or blank items", ex.getMessage());
    }

    @Test
    void throwIfEmptyWithContextUsesTheContextFunctionNotTheItem() {
        final List<String> value = List.of("non blank item");

        final EmptyList ex = assertThrows(EmptyList.class, () -> validate.throwIfEmpty(value, item -> " "));

        assertEquals("List has empty or blank items", ex.getMessage());
    }

    @Test
    void throwIfEmptyWithContextAcceptsListWithOneNonBlankContext() {
        final List<String> value = Arrays.asList("", null, "content", " ");

        assertSame(value, validate.throwIfEmpty(value, item -> item));
    }

    @Test
    void throwIfInsufficientRejectsNull() {
        final InsufficientContext ex = assertThrows(InsufficientContext.class, () -> validate.throwIfInsufficient(null, 0));

        assertEquals("The context is insufficient", ex.getMessage());
    }

    @Test
    void throwIfInsufficientRejectsListOneShorterThanSize() {
        final InsufficientContext ex = assertThrows(InsufficientContext.class,
                () -> validate.throwIfInsufficient(List.of("a", "b"), 3));

        assertEquals("The context is insufficient", ex.getMessage());
    }

    @Test
    void throwIfInsufficientAcceptsListExactlyAtSize() {
        final List<String> value = List.of("a", "b", "c");

        assertSame(value, validate.throwIfInsufficient(value, 3));
    }

    @Test
    void throwIfInsufficientAcceptsListLargerThanSize() {
        final List<String> value = List.of("a", "b", "c", "d");

        assertSame(value, validate.throwIfInsufficient(value, 3));
    }

    @Test
    void throwIfInsufficientAcceptsEmptyListWhenSizeIsZero() {
        final List<String> value = List.of();

        assertSame(value, validate.throwIfInsufficient(value, 0));
    }
}
