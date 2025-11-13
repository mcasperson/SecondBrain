package secondbrain.domain.objects;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

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
                .map(String::toLowerCase)
                .toList();

        final List<String> values = Arrays.stream(obj.getClass().getMethods())
                .filter(method -> !processedExclusions.contains(method.getName()))
                .sorted(Comparator.comparing(Method::getName))
                .filter(method -> method.getName().startsWith("get") &&
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
}
