package secondbrain.domain.objects;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;

import java.lang.reflect.Method;
import java.util.*;

/**
 * An implementation of ToStringGenerator that generates a string representation
 * of an object's getter methods, excluding those that start with "getSecret".
 */
@ApplicationScoped
public class SecretGetterGenerator implements ToStringGenerator {
    @Override
    public String generateGetterConfig(final Object obj) {
        return generateGetterConfig(obj, List.of());
    }

    @Override
    public String generateGetterConfig(final Object obj, final List<String> excludeFields) {
        if (obj == null) {
            return "";
        }

        final List<String> processedExclusions = Objects.requireNonNullElse(excludeFields, List.<String>of()).stream()
                .map(e -> e.toLowerCase(Locale.ROOT))
                .toList();

        final List<String> values = Arrays.stream(obj.getClass().getMethods())
                .filter(method -> !processedExclusions.contains(method.getName().toLowerCase(Locale.ROOT)))
                .sorted(Comparator.comparing(Method::getName))
                .filter(method -> (method.getName().startsWith("get") || method.getName().startsWith("is")) &&
                        !method.getName().equals("getClass") &&
                        !method.getName().startsWith("getSecret") &&
                        !method.getName().startsWith("getSensitive") &&
                        method.getParameterCount() == 0 &&
                        method.getReturnType() != void.class)
                .map(getterMethod -> Try.of(() -> getterMethod.invoke(obj))
                        .map(value -> getterMethod.getName() + " = " + value)
                        .getOrNull())
                .filter(Objects::nonNull)
                .toList();

        return String.join("\n", values);
    }

    @Override
    public int generateHashGetterConfig(final Object obj) {
        return generateHashGetterConfig(obj, List.of());
    }

    @Override
    public int generateHashGetterConfig(final Object obj, final List<String> excludeFields) {
        if (obj == null) {
            return 0;
        }

        final List<String> processedExclusions = Objects.requireNonNullElse(excludeFields, List.<String>of()).stream()
                .map(e -> e.toLowerCase(Locale.ROOT))
                .toList();

        final List<Object> values = Arrays.stream(obj.getClass().getMethods())
                .filter(method -> !processedExclusions.contains(method.getName().toLowerCase(Locale.ROOT)))
                .sorted(Comparator.comparing(Method::getName))
                .filter(method -> (method.getName().startsWith("get") || method.getName().startsWith("is")) &&
                        !method.getName().equals("getClass") &&
                        !method.getName().startsWith("getSecret") &&
                        !method.getName().startsWith("getSensitive") &&
                        method.getParameterCount() == 0 &&
                        method.getReturnType() != void.class)
                .map(getterMethod -> Try.of(() -> normalizeForHash(getterMethod.invoke(obj)))
                        .getOrNull())
                .filter(Objects::nonNull)
                .toList();

        return values.hashCode();
    }

    /**
     * Arrays use identity-based hashCode, so two different array instances with the same content
     * would produce different hashes. This method normalizes arrays to their content-based hash
     * so that the overall hash is stable for objects with the same values.
     */
    private static Object normalizeForHash(final Object value) {
        if (value instanceof int[] a) return Arrays.hashCode(a);
        if (value instanceof long[] a) return Arrays.hashCode(a);
        if (value instanceof double[] a) return Arrays.hashCode(a);
        if (value instanceof float[] a) return Arrays.hashCode(a);
        if (value instanceof boolean[] a) return Arrays.hashCode(a);
        if (value instanceof byte[] a) return Arrays.hashCode(a);
        if (value instanceof short[] a) return Arrays.hashCode(a);
        if (value instanceof char[] a) return Arrays.hashCode(a);
        if (value instanceof Object[] a) return Arrays.deepHashCode(a);
        return value;
    }
}
