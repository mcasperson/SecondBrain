package secondbrain.domain.logger;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.InjectionPoint;

import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

@ApplicationScoped
public class Loggers {
    @Produces
    public Logger getLogger(final InjectionPoint injectionPoint) {
        final Logger logger = Logger.getLogger(injectionPoint.getMember().getDeclaringClass()
                .getSimpleName());

        final SimpleFormatter formatter = new SimpleFormatter();

        Try.of(() -> new FileHandler("SecondBrain.log", true))
                .onSuccess(handler -> {
                    handler.setFormatter(formatter);
                    logger.addHandler(handler);
                })
                .onFailure(throwable -> logger.warning("Failed to create logger file handler: " + throwable.getMessage()));

        return logger;
    }
}