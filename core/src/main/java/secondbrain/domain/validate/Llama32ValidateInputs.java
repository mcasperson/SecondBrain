package secondbrain.domain.validate;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang.StringUtils;

@ApplicationScoped
public class Llama32ValidateInputs implements ValidateInputs {
    @Override
    public String getCommaSeparatedList(final String input) {
        if (StringUtils.isBlank(input)) {
            return "";
        }

        // llama 3.2 might try and pass you an empty list rather than nothing at all
        if (input.equals("[]")) {
            return "";
        }

        // llama 3.2 might try and pass you a string "null" rather than nothing at all
        if (input.equals("null")) {
            return "";
        }

        return input;
    }
}
