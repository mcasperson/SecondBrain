package secondbrain.domain.exceptionhandling;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.exceptions.ExternalException;

@ApplicationScoped
public class LoggingExceptionHandler implements ExceptionHandler {

    @Inject
    @ConfigProperty(name = "sb.exceptions.printstacktrace", defaultValue = "false")
    private String printStackTrace;

    @Override
    public String getExceptionMessage(final Throwable e) {
        if (e == null) {
            return "Exception was null";
        }

        if (Boolean.parseBoolean(printStackTrace) || e instanceof ExternalException) {
            return ExceptionUtils.getStackTrace(e);
        }

        if (StringUtils.isBlank(e.getMessage())) {
            return e.toString();
        }

        return e.getMessage();
    }
}
