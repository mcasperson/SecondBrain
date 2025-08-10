package secondbrain.domain.logger;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.InjectionPoint;

import java.util.logging.FileHandler;
import java.util.logging.Logger;

@ApplicationScoped
public class Loggers {
    @Produces
    public Logger getLogger(final InjectionPoint injectionPoint) {
        final Logger logger = Logger.getLogger(injectionPoint.getMember().getDeclaringClass()
                .getSimpleName());

        Try.of(() -> new FileHandler("SecondBrain.log", true))
                .onSuccess(logger::addHandler)
                .onFailure(throwable -> logger.warning("Failed to create logger file handler: " + throwable.getMessage()));

        return logger;
    }
}