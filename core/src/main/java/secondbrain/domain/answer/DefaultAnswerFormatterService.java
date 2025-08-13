package secondbrain.domain.answer;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.logging.Logger;
import java.util.regex.Pattern;

@ApplicationScoped
public class DefaultAnswerFormatterService implements AnswerFormatterService {
    @Inject
    private Instance<AnswerFormatter> answerFormatters;

    @Inject
    private Logger logger;

    @Override
    public String formatResponse(String model, String response) {
        return answerFormatters.stream()
                // Regexes might be invalid, just treat them as a false match
                .filter(b -> Try.of(() -> Pattern.compile(b.modelRegex()).matcher(model).matches())
                        .onFailure(e -> logger.warning("Invalid regex for model " + model + ": " + b.modelRegex() + ". Error: " + e.getMessage()))
                        .getOrElse(false))
                .findFirst()
                .map(formatter -> formatter.formatAnswer(response))
                .orElse(response);
    }
}
