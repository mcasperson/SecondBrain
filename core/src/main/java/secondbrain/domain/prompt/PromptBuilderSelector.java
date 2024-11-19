package secondbrain.domain.prompt;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.regex.Pattern;

/**
 * Provides a way to select the correct prompt builder based on the model.
 */
@ApplicationScoped
public class PromptBuilderSelector {

    @Inject
    @Any
    private Instance<PromptBuilder> builders;

    public PromptBuilder getPromptBuilder(final String model) {
        return builders.stream()
                .filter(b -> Pattern.compile(b.modelRegex()).matcher(model).matches())
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No prompt builder found for model: " + model));
    }
}
