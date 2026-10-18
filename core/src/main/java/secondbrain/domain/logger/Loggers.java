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
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.logging.Handler;

import java.util.Properties;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

@ApplicationScoped
public class Loggers {

    @Nullable
    private Handler fileHandler;

    @Nullable
    private Handler consoleHandler;

    @Inject
    @ConfigProperty(name = "sb.logging.redacted", defaultValue = "false")
    private Boolean redacted;

    @PostConstruct
    private void init() {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%4$s: %5$s %n");

        if (this.redacted) {
            configureRedactedHandlers();
        } else {
            configureRegularHandlers();
        }

    }

    private void configureRegularHandlers() {
        final SimpleFormatter formatter = new SimpleFormatter();

        this.fileHandler = Try.of(() -> new FileHandler("SecondBrain.log", true))
                .onSuccess(handler -> handler.setFormatter(formatter))
                .getOrNull();

        this.consoleHandler = new ConsoleHandler();
    }

    private void configureRedactedHandlers() {
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