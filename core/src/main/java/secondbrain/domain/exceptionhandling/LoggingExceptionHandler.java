package secondbrain.domain.exceptionhandling;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.exceptions.ExternalFailure;

@ApplicationScoped
public class LoggingExceptionHandler implements ExceptionHandler {

    @Inject
    @ConfigProperty(name = "sb.exceptions.printstacktrace", defaultValue = "false")
    private String printStackTrace;

    @Override
    public String getExceptionMessage(final Throwable e) {
        if (e == null) {
            return "Unknown error";
        }

        if (Boolean.parseBoolean(printStackTrace) || e instanceof ExternalFailure) {
            return ExceptionUtils.getStackTrace(e);
        }

        return e.getMessage();
    }
}
