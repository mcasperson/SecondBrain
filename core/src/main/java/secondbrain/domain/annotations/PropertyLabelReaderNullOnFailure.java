package secondbrain.domain.annotations;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

@ApplicationScoped
public class PropertyLabelReaderNullOnFailure implements PropertyValueReader {
    public List<PropertyLabelDescriptionValue> getValues(final Object object) {
        if (object == null) {
            return List.of();
        }

        return Stream.of(Objects.requireNonNullElse(object.getClass().getRecordComponents(), new RecordComponent[0]))
                .map(component -> new AnnotationComponent(component.getAnnotation(PropertyLabel.class), component))
                .filter(ac -> ac.label != null)
                .map(ac -> new AnnotationValue(ac.label, Try.of(() -> ac.component.getAccessor().invoke(object)).getOrNull()))
                .filter(vc -> vc.value != null)
                .map(vc -> new PropertyLabelDescriptionValue(vc.label.description(), vc.value))
                .toList();
    }

    record AnnotationComponent(PropertyLabel label, RecordComponent component) { }

    record AnnotationValue(PropertyLabel label, Object value) { }
}



