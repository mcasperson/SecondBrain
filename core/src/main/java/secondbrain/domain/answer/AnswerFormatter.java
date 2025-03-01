package secondbrain.domain.answer;

/**
 * Defines a service used to parse the response given by a model.
 */
public interface AnswerFormatter {
    String modelRegex();

    String formatAnswer(String answer);
}
