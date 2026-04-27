package secondbrain.domain.annotations;

import java.util.List;

public interface PropertyValueReader {
    List<PropertyLabelDescriptionValue> getValues(final Object object);
}
