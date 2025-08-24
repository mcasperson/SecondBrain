package secondbrain.domain.converter;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class NoOpStringConverter implements StringConverter {
    @Override
    public String getFormat() {
        return "no-op";
    }

    @Override
    public String convert(final String response) {
        return response;
    }
}
