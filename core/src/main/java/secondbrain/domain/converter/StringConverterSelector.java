package secondbrain.domain.converter;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Provides a way to select the correct prompt builder based on the model.
 */
@ApplicationScoped
public class StringConverterSelector {

    @Inject
    @Any
    private Instance<StringConverter> converters;

    @Inject
    private NoOpStringConverter noOpStringConverter;

    public StringConverter getStringConverter(final String format) {
        return converters.stream()
                .filter(b -> b.getFormat().equals(format))
                .findFirst()
                .orElse(noOpStringConverter);
    }
}
