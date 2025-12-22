package secondbrain.domain.logger;

import io.vavr.control.Try;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.InjectionPoint;
import org.jspecify.annotations.Nullable;

import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

@ApplicationScoped
public class Loggers {

    @Nullable
    private FileHandler fileHandler;

    @PostConstruct
    private void init() {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%4$s: %5$s %n");
        final SimpleFormatter formatter = new SimpleFormatter();
        this.fileHandler = Try.of(() -> new FileHandler("SecondBrain.log", true))
                .onSuccess(handler -> handler.setFormatter(formatter))
                .getOrNull();
    }

    @Produces
    public Logger getLogger(final InjectionPoint injectionPoint) {
        final Logger logger = Logger.getLogger(
                injectionPoint.getMember().getDeclaringClass().getName());

        if (fileHandler != null) {
            logger.addHandler(fileHandler);
        }

        return logger;
    }
}