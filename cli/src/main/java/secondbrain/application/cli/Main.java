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
import secondbrain.domain.handler.PromptHandlerResponse;
import secondbrain.domain.json.JsonDeserializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;


public class Main {
    @Inject
    private PromptHandler promptHandler;

    @Inject
    private MarkdnParser markdnParser;

    @Inject
    private JsonDeserializer jsonDeserializer;

    @Inject
    @ConfigProperty(name = "sb.output.file")
    private Optional<String> file;

    @Inject
    @ConfigProperty(name = "sb.output.appendFile", defaultValue = "false")
    private Boolean appendToOutputFile;

    @Inject
    @ConfigProperty(name = "sb.output.annotationsFile")
    private Optional<String> annotationsFile;

    @Inject
    @ConfigProperty(name = "sb.output.linksFile")
    private Optional<String> linksFile;

    @Inject
    @ConfigProperty(name = "sb.output.debugFile")
    private Optional<String> debugFile;

    @Inject
    @ConfigProperty(name = "sb.output.printAnnotations", defaultValue = "true")
    private Boolean printAnnotations;

    @Inject
    @ConfigProperty(name = "sb.output.directory", defaultValue = ".")
    private String directory;

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
                .map(response -> markdownParsing
                        ? response.updateResponseText(markdnParser)
                        : response)
                .onSuccess(this::printOutput)
                .onSuccess(this::writeAnnotations)
                .onSuccess(this::writeLinks)
                .onSuccess(this::writeDebug)
                .onSuccess(this::writeOutput)
                .onSuccess(this::saveMetadata)
                .onSuccess(this::saveIntermediateResults)
                .onFailure(e -> System.err.println("Failed to process prompt: " + e.getMessage()));
    }

    private void printOutput(final PromptHandlerResponse content) {
        System.out.println(content.getResponseText());

        if (printAnnotations) {
            if (StringUtils.isNotBlank(content.getAnnotations())) {
                System.out.println(content.getAnnotations());
            }
            if (StringUtils.isNotBlank(content.getLinks())) {
                System.out.println("Links:");
                System.out.println(content.getLinks());
            }
            if (StringUtils.isNotBlank(content.getDebugInfo())) {
                System.out.println(content.getDebugInfo());
            }
        }
    }

    private void writeAnnotations(final PromptHandlerResponse content) {
        if (annotationsFile.isEmpty()) {
            return;
        }

        final String annotations = content.getAnnotations() + content.getDebugInfo();

        if (StringUtils.isNotBlank(annotations)) {
            Try.run(() -> Files.write(getFilePath(annotationsFile.get()), annotations.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))
                    .onFailure(e -> System.err.println("Failed to write to file: " + e.getMessage()));
        }
    }

    private void writeLinks(final PromptHandlerResponse content) {
        if (linksFile.isEmpty()) {
            return;
        }

        if (StringUtils.isNotBlank(content.getLinks())) {
            Try.run(() -> Files.write(
                            getFilePath(linksFile.get()),
                            ("Links:" + System.lineSeparator() + content.getLinks()).getBytes(),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING))
                    .onFailure(e -> System.err.println("Failed to write to file: " + e.getMessage()));
        }
    }

    private void writeDebug(final PromptHandlerResponse content) {
        if (debugFile.isEmpty()) {
            return;
        }

        if (StringUtils.isNotBlank(content.getDebugInfo())) {
            Try.run(() -> Files.write(getFilePath(debugFile.get()), content.getDebugInfo().getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))
                    .onFailure(e -> System.err.println("Failed to write to file: " + e.getMessage()));
        }
    }

    private void writeOutput(final PromptHandlerResponse content) {
        if (file.isEmpty()) {
            return;
        }

        final StandardOpenOption option = appendToOutputFile
                ? StandardOpenOption.APPEND
                : StandardOpenOption.TRUNCATE_EXISTING;

        Try.run(() -> Files.write(getFilePath(file.get()), content.getResponseText().getBytes(), StandardOpenOption.CREATE, option))
                .onFailure(e -> System.err.println("Failed to write to file: " + e.getMessage()));
    }

    private void saveMetadata(final PromptHandlerResponse content) {
        content
                .getMetaObjectResults()
                .stream()
                .filter(Objects::nonNull)
                .filter(meta -> StringUtils.isNotBlank(meta.getFilename()))
                .forEach(meta -> Try.of(() -> Files.write(
                                getFilePath(meta.getFilename()),
                                jsonDeserializer.serialize(meta).getBytes(),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING))
                        .onFailure(e -> System.err.println("Failed to write metadata to files: " + e.getMessage())));
    }

    private void saveIntermediateResults(final PromptHandlerResponse content) {
        content
                .getIntermediateResults()
                .stream()
                .filter(Objects::nonNull)
                .filter(meta -> StringUtils.isNotBlank(meta.filename()) && meta.content() != null)
                .forEach(meta -> Try.of(() -> Files.write(
                                getFilePath(meta.filename()),
                                meta.content().getBytes(),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING))
                        .onFailure(e -> System.err.println("Failed to write intermediate result to files: " + e.getMessage())));
    }

    private Path getFilePath(final String path) {
        final Path directoryPath = Paths.get(path);
        if (directoryPath.isAbsolute()) {
            return directoryPath;
        } else if (StringUtils.isNotBlank(directory)) {
            return Paths.get(directory, path);
        } else {
            return directoryPath;
        }
    }
}
