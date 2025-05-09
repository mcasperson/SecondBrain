package secondbrain.application.cli;

import io.vavr.control.Try;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import secondbrain.Marker;
import secondbrain.domain.converter.MarkdnParser;
import secondbrain.domain.handler.PromptHandler;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Optional;


public class Main {
    @Inject
    private PromptHandler promptHandler;

    @Inject
    private MarkdnParser markdnParser;

    @Inject
    @ConfigProperty(name = "sb.output.file")
    private Optional<String> file;

    public static void main(final String[] args) {
        final Weld weld = new Weld();
        /*
        For the life of me I could not get Weld to find beans in the service module without manually adding a class in
        a shared ancestor package and then scanning recursively. So the marker class exists to help Weld scan for
        annotated classes in an Uber JAR.
         */
        try (WeldContainer weldContainer = weld.addBeanClass(Main.class).addPackages(true, Marker.class).initialize()) {
            weldContainer.select(Main.class).get().entry(args);
        }
    }

    private String getPrompt(final String[] args) {
        if (args.length > 0 && !StringUtils.isBlank(args[0])) {
            System.err.println("Prompt: " + args[0]);
            return args[0];
        }

        throw new RuntimeException("No prompt specified");
    }

    public void entry(final String[] args) {
        final boolean markdownParsing = args.length > 1 && "markdn".equals(args[1]);
        Try.of(() -> promptHandler.handlePrompt(Map.of(), getPrompt(args)))
                .map(response -> markdownParsing ? markdnParser.printMarkDn(response) : response)
                .onSuccess(System.out::println)
                .onSuccess(this::writeOutput)
                .onFailure(e -> System.err.println("Failed to process prompt: " + e.getMessage()));
    }

    private void writeOutput(final String content) {
        if (file.isEmpty()) {
            return;
        }

        Try.run(() -> Files.write(Paths.get(file.get()), content.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))
                .onFailure(e -> System.err.println("Failed to write to file: " + e.getMessage()));
    }
}
