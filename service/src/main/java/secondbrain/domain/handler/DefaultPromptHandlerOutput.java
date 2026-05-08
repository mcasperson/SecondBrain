package secondbrain.domain.handler;

import io.smallrye.common.annotation.Identifier;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.files.FileWriter;
import secondbrain.domain.files.PathBuilder;
import secondbrain.domain.json.JsonDeserializer;
import secondbrain.domain.sanitize.SanitizeDocument;

import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

@ApplicationScoped
public class DefaultPromptHandlerOutput implements PromptHandlerOutput {
    @Inject
    @ConfigProperty(name = "sb.output.printAnnotations", defaultValue = "true")
    private Boolean printAnnotations;

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
    @Identifier("financialLocationContactRedaction")
    private SanitizeDocument sanitizeDocument;

    @Inject
    @ConfigProperty(name = "sb.output.directory", defaultValue = ".")
    private String directory;

    @Inject
    @ConfigProperty(name = "sb.output.file")
    private Optional<String> file;

    @Inject
    private PathBuilder pathBuilder;

    @Inject
    private Logger logger;

    @Inject
    private FileWriter fileWriter;

    @Inject
    private JsonDeserializer jsonDeserializer;


    public void printOutput(final PromptHandlerResponse content) {
        final String redactedOutput = Objects.requireNonNullElse(sanitizeDocument.sanitize(content.getResponseText()), "");

        System.out.println(redactedOutput);

        if (printAnnotations) {
            if (StringUtils.isNotBlank(content.getAnnotations())) {
                System.out.println(sanitizeDocument.sanitize(content.getAnnotations()));
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

    public void writeAnnotations(final PromptHandlerResponse content) {
        if (annotationsFile.isEmpty()) {
            return;
        }

        final String annotations = Objects.requireNonNullElse(sanitizeDocument.sanitize(content.getAnnotations() + content.getDebugInfo()), "");

        if (StringUtils.isNotBlank(annotations)) {
            Try.run(() -> fileWriter.write(pathBuilder.getFilePath(directory, annotationsFile.get()), annotations))
                    .onFailure(e -> logger.severe("Failed to write annotations to file: " + e.getMessage()));
        }
    }

    public void writeLinks(final PromptHandlerResponse content) {
        if (linksFile.isEmpty()) {
            return;
        }

        if (StringUtils.isNotBlank(content.getLinks())) {
            Try.run(() -> fileWriter.write(
                            pathBuilder.getFilePath(directory, linksFile.get()),
                            "Links:" + System.lineSeparator() + content.getLinks()))
                    .onFailure(e -> logger.severe("Failed to write links to file: " + e.getMessage()));
        }
    }

    public void writeDebug(final PromptHandlerResponse content) {
        if (debugFile.isEmpty()) {
            return;
        }

        if (StringUtils.isNotBlank(content.getDebugInfo())) {
            Try.run(() -> fileWriter.write(pathBuilder.getFilePath(directory, debugFile.get()), content.getDebugInfo()))
                    .onFailure(e -> logger.severe("Failed to write debug to file: " + e.getMessage()));
        }
    }

    public void writeOutput(final PromptHandlerResponse content) {
        if (file.isEmpty()) {
            return;
        }

        final String redactedOutput = Objects.requireNonNullElse(sanitizeDocument.sanitize(content.getResponseText()), "");

        if (appendToOutputFile) {
            fileWriter.append(pathBuilder.getFilePath(directory, file.get()), redactedOutput);
        } else {
            fileWriter.write(pathBuilder.getFilePath(directory, file.get()), redactedOutput);
        }
    }

    public void saveMetadata(final PromptHandlerResponse content) {
        content
                .getMetaObjectResults()
                .stream()
                .filter(Objects::nonNull)
                .filter(meta -> StringUtils.isNotBlank(meta.getFilename()))
                .peek(meta -> logger.info("Saving metadata: " + pathBuilder.getFilePath(directory, meta.getFilename())))
                .forEach(meta -> Try.run(() -> fileWriter.write(
                                pathBuilder.getFilePath(directory, meta.getFilename()),
                                jsonDeserializer.serialize(meta)))
                        .onFailure(e -> logger.severe("Failed to write metadata to files: " + e.getMessage())));
    }

    public void saveIntermediateResults(final PromptHandlerResponse content) {
        content
                .getIntermediateResults()
                .stream()
                .filter(Objects::nonNull)
                .filter(meta -> StringUtils.isNotBlank(meta.filename()) && meta.content() != null)
                .peek(meta -> logger.info("Saving intermediate result: " + pathBuilder.getFilePath(directory, meta.filename())))
                .forEach(meta -> Try.run(() -> fileWriter.write(
                                pathBuilder.getFilePath(directory, meta.filename()),
                                Objects.requireNonNullElse(sanitizeDocument.sanitize(meta.content()), "")))
                        .onFailure(e -> logger.severe("Failed to write intermediate result to files: " + e.getMessage())));
    }
}
