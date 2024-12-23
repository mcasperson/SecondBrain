package secondbrain.domain.config;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.validate.ValidateString;

import java.util.Map;
import java.util.Optional;

/**
 * Represents the common configuration of a model.
 */
@ApplicationScoped
public class ModelConfig {
    @Inject
    @ConfigProperty(name = "sb.ollama.model", defaultValue = "llama3.2")
    private String model;

    @Inject
    @ConfigProperty(name = "sb.ollama.contextwindow")
    private Optional<String> contextWindow;

    @Inject
    private ValidateString validateString;

    public String getModel() {
        return model;
    }

    public String getCalculatedModel(final Map<String, String> context) {
        return Try.of(() -> context.get("custom_model"))
                .mapTry(validateString::throwIfEmpty)
                .recover(e -> model)
                .get();
    }

    public Optional<String> getContextWindow() {
        return contextWindow;
    }

    public Integer getCalculatedContextWindow() {
        return Try.of(contextWindow::get)
                .map(Integer::parseInt)
                .recover(e -> Constants.DEFAULT_CONTENT_WINDOW)
                .get();
    }

    public Integer getCalculatedContextWindowChars() {
        return getCalculatedContextWindow() == null
                ? Constants.DEFAULT_MAX_CONTEXT_LENGTH
                : (int) (getCalculatedContextWindow() * Constants.CONTENT_WINDOW_BUFFER * Constants.CHARACTERS_PER_TOKEN);
    }
}
