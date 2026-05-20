package secondbrain.domain.handler;

import io.smallrye.common.annotation.Identifier;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.files.FileWriter;
import secondbrain.domain.files.PathBuilder;
import secondbrain.domain.json.JsonDeserializer;
import secondbrain.domain.sanitize.SanitizeDocument;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

@ApplicationScoped
public class DefaultPromptHandlerOutput implements PromptHandlerOutput {
    private static final String OUTPUT_FILE = "PromptHandlerOutputOutputFile";
    private static final String PRINT_ANNOTATIONS = "PromptHandlerOutputPrintAnnotations";
    private static final String APPEND_TO_OUTPUT_FILE = "PromptHandlerOutputAppendToOutputFile";
    private static final String ANNOTATIONS_FILE = "PromptHandlerOutputAnnotationsFile";
    private static final String LINKS_FILE = "PromptHandlerOutputLinksFile";
    private static final String DEBUG_FILE = "PromptHandlerOutputDebugFile";
    private static final String OUTPUT_DIRECTORY = "PromptHandlerOutputOutputDirectory";

    @Inject
    @ConfigProperty(name = "sb.output.printAnnotations")
    private Optional<String> printAnnotations;

    @Inject
    @ConfigProperty(name = "sb.output.appendFile")
    private Optional<String> appendToOutputFile;

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
    @ConfigProperty(name = "sb.output.directory")
    private Optional<String> directory;

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

    @Inject
    private ArgsAccessor argsAccessor;

    private String getOutputFile(final Map<String, String> context) {
        return argsAccessor.getArgument(
                file::get,
                List.of(),
                context,
                "",
                OUTPUT_FILE,
                "").getSafeValue();
    }

    private Optional<String> getPrintAnnotations(final Map<String, String> context) {
        final String value = argsAccessor.getArgument(
                printAnnotations::get,
                List.of(),
                context,
                "",
                PRINT_ANNOTATIONS,
                "true").getSafeValue();
        return StringUtils.isBlank(value) ? Optional.empty() : Optional.of(value);
    }

    private Optional<String> getAppendToOutputFile(final Map<String, String> context) {
        final String value = argsAccessor.getArgument(
                appendToOutputFile::get,
                List.of(),
                context,
                "",
                APPEND_TO_OUTPUT_FILE,
                "false").getSafeValue();
        return StringUtils.isBlank(value) ? Optional.empty() : Optional.of(value);
    }

    private Optional<String> getAnnotationsFile(final Map<String, String> context) {
        final String value = argsAccessor.getArgument(
                annotationsFile::get,
                List.of(),
                context,
                "",
                ANNOTATIONS_FILE,
                "").getSafeValue();
        return StringUtils.isBlank(value) ? Optional.empty() : Optional.of(value);
    }

    private Optional<String> getLinksFile(final Map<String, String> context) {
        final String value = argsAccessor.getArgument(
                linksFile::get,
                List.of(),
                context,
                "",
                LINKS_FILE,
                "").getSafeValue();
        return StringUtils.isBlank(value) ? Optional.empty() : Optional.of(value);
    }

    private Optional<String> getDebugFile(final Map<String, String> context) {
        final String value = argsAccessor.getArgument(
                debugFile::get,
                List.of(),
                context,
                "",
                DEBUG_FILE,
                "").getSafeValue();
        return StringUtils.isBlank(value) ? Optional.empty() : Optional.of(value);
    }

    private Optional<String> getDirectory(final Map<String, String> context) {
        final String value = argsAccessor.getArgument(
                directory::get,
                List.of(),
                context,
                "",
                OUTPUT_DIRECTORY,
                ".").getSafeValue();
        return StringUtils.isBlank(value) ? Optional.empty() : Optional.of(value);
    }

    @Override
    public void printOutput(final PromptHandlerResponse content) {
        printOutput(content, Map.of());
    }

    @Override
    public void writeAnnotations(final PromptHandlerResponse content) {
        writeAnnotations(content, Map.of());
    }

    @Override
    public void writeLinks(final PromptHandlerResponse content) {
        writeLinks(content, Map.of());
    }

    @Override
    public void writeDebug(final PromptHandlerResponse content) {
        writeDebug(content, Map.of());
    }

    @Override
    public void writeOutput(final PromptHandlerResponse content) {
        writeOutput(content, Map.of());
    }

    @Override
    public void saveMetadata(final PromptHandlerResponse content) {
        saveMetadata(content, Map.of());
    }

    @Override
    public void saveIntermediateResults(final PromptHandlerResponse content) {
        saveIntermediateResults(content, Map.of());
    }

    public void printOutput(final PromptHandlerResponse content, final Map<String, String> context) {
        final String redactedOutput = Objects.requireNonNullElse(sanitizeDocument.sanitize(content.getResponseText()), "");

        System.out.println(redactedOutput);

        if (getPrintAnnotations(context).map(Boolean::valueOf).orElse(true)) {
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

    public void writeAnnotations(final PromptHandlerResponse content, final Map<String, String> context) {
        if (getAnnotationsFile(context).isEmpty()) {
            return;
        }

        final String annotations = Objects.requireNonNullElse(sanitizeDocument.sanitize(content.getAnnotations() + content.getDebugInfo()), "");

        if (StringUtils.isNotBlank(annotations)) {
            Try.run(() -> fileWriter.write(pathBuilder.getFilePath(getDirectory(context).get(), getAnnotationsFile(context).get()), annotations))
                    .onFailure(e -> logger.severe("Failed to write annotations to file: " + e.getMessage()));
        }
    }

    public void writeLinks(final PromptHandlerResponse content, final Map<String, String> context) {
        if (getLinksFile(context).isEmpty()) {
            return;
        }

        if (StringUtils.isNotBlank(content.getLinks())) {
            Try.run(() -> fileWriter.write(
                            pathBuilder.getFilePath(getDirectory(context).get(), getLinksFile(context).get()),
                            "Links:" + System.lineSeparator() + content.getLinks()))
                    .onFailure(e -> logger.severe("Failed to write links to file: " + e.getMessage()));
        }
    }

    public void writeDebug(final PromptHandlerResponse content, final Map<String, String> context) {
        if (getDebugFile(context).isEmpty()) {
            return;
        }

        if (StringUtils.isNotBlank(content.getDebugInfo())) {
            Try.run(() -> fileWriter.write(pathBuilder.getFilePath(getDirectory(context).get(), getDebugFile(context).get()), content.getDebugInfo()))
                    .onFailure(e -> logger.severe("Failed to write debug to file: " + e.getMessage()));
        }
    }

    public void writeOutput(final PromptHandlerResponse content, final Map<String, String> context) {
        if (StringUtils.isBlank(getOutputFile(context))) {
            return;
        }

        final String redactedOutput = Objects.requireNonNullElse(sanitizeDocument.sanitize(content.getResponseText()), "");

        if (getAppendToOutputFile(context).map(Boolean::valueOf).orElse(false)) {
            fileWriter.append(pathBuilder.getFilePath(getDirectory(context).get(), getOutputFile(context)), redactedOutput);
        } else {
            fileWriter.write(pathBuilder.getFilePath(getDirectory(context).get(), getOutputFile(context)), redactedOutput);
        }
    }

    public void saveMetadata(final PromptHandlerResponse content, final Map<String, String> context) {
        content
                .getMetaObjectResults()
                .stream()
                .filter(Objects::nonNull)
                .filter(meta -> StringUtils.isNotBlank(meta.getFilename()))
                .peek(meta -> logger.info("Saving metadata: " + pathBuilder.getFilePath(getDirectory(context).get(), meta.getFilename())))
                .forEach(meta -> Try.run(() -> fileWriter.write(
                                pathBuilder.getFilePath(getDirectory(context).get(), meta.getFilename()),
                                jsonDeserializer.serialize(meta)))
                        .onFailure(e -> logger.severe("Failed to write metadata to files: " + e.getMessage())));
    }

    public void saveIntermediateResults(final PromptHandlerResponse content, final Map<String, String> context) {
        content
                .getIntermediateResults()
                .stream()
                .filter(Objects::nonNull)
                .filter(meta -> StringUtils.isNotBlank(meta.filename()) && meta.content() != null)
                .peek(meta -> logger.info("Saving intermediate result: dir " + getDirectory(context).get() + " file " + meta.filename() + " final path " + pathBuilder.getFilePath(getDirectory(context).get(), meta.filename())))
                .forEach(meta -> Try.run(() -> fileWriter.write(
                                pathBuilder.getFilePath(getDirectory(context).get(), meta.filename()),
                                Objects.requireNonNullElse(sanitizeDocument.sanitize(meta.content()), "")))
                        .onFailure(e -> logger.severe("Failed to write intermediate result to files: " + e.getMessage())));
    }
}
