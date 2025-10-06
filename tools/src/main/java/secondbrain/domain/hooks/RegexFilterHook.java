package secondbrain.domain.hooks;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.context.RagDocumentContext;

import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Pattern;

@ApplicationScoped
public class RegexFilterHook implements PreProcessingHook {
    @Inject
    @ConfigProperty(name = "sb.regexfilterhook.regex")
    private Optional<String> regex;

    @Inject
    private Logger logger;

    @Override
    public <T> List<RagDocumentContext<T>> process(final String toolName, final List<RagDocumentContext<T>> ragDocumentContexts) {
        if (ragDocumentContexts == null) {
            return List.of();
        }

        if (regex == null || regex.isEmpty()) {
            return ragDocumentContexts;
        }

        final Pattern pattern = Try.of(() -> Pattern.compile(regex.get()))
                .onFailure(ex -> logger.severe("Failed to compile regex pattern: " + ex.getMessage()))
                .getOrNull();

        if (pattern == null) {
            return ragDocumentContexts;
        }

        return ragDocumentContexts.stream()
                .filter(ragDoc -> !pattern.matcher(ragDoc.document()).find())
                .toList();
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }
}
