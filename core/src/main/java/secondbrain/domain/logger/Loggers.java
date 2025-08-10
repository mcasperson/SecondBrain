package secondbrain.domain.logger;

import io.vavr.control.Try;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.InjectionPoint;
import org.jspecify.annotations.Nullable;

import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

@ApplicationScoped
public class Loggers {

    @Nullable
    private FileHandler fileHandler;

    @PostConstruct
    private void init() {
        final SimpleFormatter formatter = new SimpleFormatter();
        this.fileHandler = Try.of(() -> new FileHandler("SecondBrain.log", true))
                .onSuccess(handler -> handler.setFormatter(formatter))
                .getOrNull();
    }

    @Produces
    public Logger getLogger(final InjectionPoint injectionPoint) {
        final Logger logger = Logger.getLogger(injectionPoint.getMember().getDeclaringClass()
                .getSimpleName());

        logger.setLevel(Level.INFO);

        if (fileHandler != null) {
            logger.addHandler(fileHandler);
        }

        return logger;
    }
}