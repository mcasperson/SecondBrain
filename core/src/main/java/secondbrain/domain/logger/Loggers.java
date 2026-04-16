package secondbrain.domain.logger;

import ai.philterd.phileas.PhileasConfiguration;
import ai.philterd.phileas.services.context.DefaultContextService;
import ai.philterd.phileas.services.filters.filtering.PlainTextFilterService;
import io.vavr.control.Try;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.InjectionPoint;
import org.jspecify.annotations.Nullable;

import java.util.Properties;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

@ApplicationScoped
public class Loggers {

    @Nullable
    private PhileasRedactionHandler fileHandler;

    @Nullable
    private PhileasRedactionHandler consoleHandler;

    @PostConstruct
    private void init() {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%4$s: %5$s %n");
        final SimpleFormatter formatter = new SimpleFormatter();

        final PhileasConfiguration config = new PhileasConfiguration(new Properties());
        final PlainTextFilterService filterService = new PlainTextFilterService(
                config,
                new DefaultContextService(),
                null,
                null);

        this.fileHandler = Try.of(() -> new FileHandler("SecondBrain.log", true))
                .onSuccess(handler -> handler.setFormatter(formatter))
                .map(handler -> new PhileasRedactionHandler(handler, filterService))
                .getOrNull();

        this.consoleHandler = new PhileasRedactionHandler(new ConsoleHandler(), filterService);
    }

    @Produces
    public Logger getLogger(final InjectionPoint injectionPoint) {
        final Logger logger = Logger.getLogger(
                injectionPoint.getMember().getDeclaringClass().getName());

        // Only configure once to avoid duplicate handlers
        if (logger.getHandlers().length > 0) {
            return logger;
        }

        logger.setUseParentHandlers(false);

        if (consoleHandler != null) {
            logger.addHandler(consoleHandler);
        }

        if (fileHandler != null) {
            logger.addHandler(fileHandler);
        }

        return logger;
    }
}