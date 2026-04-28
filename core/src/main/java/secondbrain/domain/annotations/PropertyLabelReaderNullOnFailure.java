package secondbrain.domain.annotations;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@ApplicationScoped
public class PropertyLabelReaderNullOnFailure implements PropertyValueReader {
    public List<PropertyLabelDescriptionValue> getValues(final Object object) {
        if (object == null) {
            return List.of();
        }

        final var results = new ArrayList<PropertyLabelDescriptionValue>();

        // Handle record components
        final RecordComponent[] recordComponents = object.getClass().getRecordComponents();
        if (recordComponents != null && recordComponents.length > 0) {
            Stream.of(recordComponents)
                    .map(component -> new AnnotationComponent(component.getAnnotation(PropertyLabel.class), component))
                    .filter(ac -> ac.label != null)
                    .map(ac -> new AnnotationValue(ac.label, Try.of(() -> ac.component.getAccessor().invoke(object)).getOrNull()))
                    .filter(vc -> vc.value != null)
                    .map(vc -> new PropertyLabelDescriptionValue(vc.label.description(), vc.value))
                    .forEach(results::add);
        } else {
            // Handle getters and fields on non-record objects
            results.addAll(readGetters(object));
            results.addAll(readFields(object));
        }

        return results;
    }

    private List<PropertyLabelDescriptionValue> readGetters(final Object object) {
        final var results = new ArrayList<PropertyLabelDescriptionValue>();

        Class<?> clazz = object.getClass();
        while (clazz != null && clazz != Object.class) {
            for (final Method method : clazz.getDeclaredMethods()) {
                // Only consider getter methods: zero parameters, non-void return type, starts with "get" or "is"
                if (method.getParameterCount() != 0 || method.getReturnType().equals(Void.TYPE)) {
                    continue;
                }
                final String methodName = method.getName();
                if (!methodName.startsWith("get") && !methodName.startsWith("is")) {
                    continue;
                }
                method.setAccessible(true);
                final PropertyLabel label = method.getAnnotation(PropertyLabel.class);
                if (label != null) {
                    final Object value = Try.of(() -> method.invoke(object)).getOrNull();
                    if (value != null) {
                        results.add(new PropertyLabelDescriptionValue(label.description(), value));
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }

        return results;
    }

    private List<PropertyLabelDescriptionValue> readFields(final Object object) {
        final var results = new ArrayList<PropertyLabelDescriptionValue>();

        Class<?> clazz = object.getClass();
        while (clazz != null && clazz != Object.class) {
            for (final java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                field.setAccessible(true);
                final PropertyLabel label = field.getAnnotation(PropertyLabel.class);
                if (label != null) {
                    final Object value = Try.of(() -> field.get(object)).getOrNull();
                    if (value != null) {
                        results.add(new PropertyLabelDescriptionValue(label.description(), value));
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }

        return results;
    }

    record AnnotationComponent(PropertyLabel label, RecordComponent component) { }

    record AnnotationValue(PropertyLabel label, Object value) { }
}
