package secondbrain.domain.handler;

import io.smallrye.common.annotation.Identifier;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.files.FileWriter;
import secondbrain.domain.files.PathBuilder;
import secondbrain.domain.json.JsonDeserializerJackson;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.sanitize.SanitizeDocument;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.tooldefs.IntermediateResult;
import secondbrain.domain.tooldefs.MetaObjectResult;
import secondbrain.domain.tooldefs.MetaObjectResults;
import secondbrain.domain.validate.ValidateStringBlank;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(DefaultPromptHandlerOutput.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(JsonDeserializerJackson.class)
@AddBeanClasses(Loggers.class)
class DefaultPromptHandlerOutputTest {
    private static final String DIR = "out-dir";
    private static final String NL = System.lineSeparator();

    private final RecordingFileWriter fileWriter = new RecordingFileWriter();
    private final List<LogRecord> severeLogs = new CopyOnWriteArrayList<>();
    private final Handler logHandler = new Handler() {
        @Override
        public void publish(final LogRecord record) {
            if (record.getLevel() == Level.SEVERE) {
                severeLogs.add(record);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    // JUL only holds weak references to loggers, so keep a strong reference while the handler is attached
    private final Logger outputLogger = Logger.getLogger(DefaultPromptHandlerOutput.class.getName());
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private PrintStream originalOut = System.out;

    @Inject
    private DefaultPromptHandlerOutput output;

    @Produces
    FileWriter produceFileWriter() {
        return fileWriter;
    }

    @Produces
    PathBuilder producePathBuilder() {
        return (directory, path) -> Paths.get(directory, path);
    }

    /**
     * Replaces "secret" with "[redacted]", and returns null for the text "NULL".
     */
    @Produces
    @Identifier("financialLocationContactRedaction")
    SanitizeDocument produceSanitizeDocument() {
        return new SanitizeDocument() {
            @Override
            public @Nullable String sanitize(@Nullable final String document) {
                if (document == null || document.equals("NULL")) {
                    return null;
                }
                return document.replace("secret", "[redacted]");
            }

            @Override
            public @Nullable String sanitize(@Nullable final String document, final boolean unsanitize) {
                return sanitize(document);
            }
        };
    }

    @BeforeEach
    void setUp() {
        TestConfigUtil.registerConfig(Map.of());
        originalOut = System.out;
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
        outputLogger.addHandler(logHandler);
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        outputLogger.removeHandler(logHandler);
    }

    private static PromptResponseSimple response(final String text, final String annotations, final String links, final String debug) {
        return new PromptResponseSimple(text, annotations, links, debug, List.of(), List.of());
    }

    private static Map<String, String> context(final String... nameValues) {
        final Map<String, String> context = new HashMap<>();
        context.put("PromptHandlerOutputOutputDirectory", DIR);
        for (int i = 0; i < nameValues.length; i += 2) {
            context.put(nameValues[i], nameValues[i + 1]);
        }
        return context;
    }

    private static String path(final String file) {
        return Paths.get(DIR, file).toString();
    }

    private String printed() {
        return stdout.toString(StandardCharsets.UTF_8);
    }

    @Test
    void printOutputPrintsRedactedResponseAnnotationsLinksAndDebugByDefault() {
        output.printOutput(response("the secret answer", "secret notes", "* link", "debug info"), Map.of());

        assertEquals("the [redacted] answer" + NL + "[redacted] notes" + NL + "Links:" + NL + "* link" + NL + "debug info" + NL, printed());
    }

    @Test
    void printOutputSkipsBlankExtras() {
        output.printOutput(response("answer", " ", "", ""), Map.of());

        assertEquals("answer" + NL, printed());
    }

    @Test
    void printOutputOmitsExtrasWhenAnnotationsAreDisabled() {
        output.printOutput(response("answer", "notes", "* link", "debug"), Map.of("PromptHandlerOutputPrintAnnotations", "false"));

        assertEquals("answer" + NL, printed());
    }

    @Test
    void printOutputPrintsEmptyLineWhenSanitizerReturnsNull() {
        output.printOutput(response("NULL", "", "", ""), Map.of());

        assertEquals(NL, printed());
    }

    @Test
    void printOutputWithoutContextUsesConfiguration() {
        TestConfigUtil.registerConfig(Map.of("sb.output.printAnnotations", "false"));

        output.printOutput(response("answer", "notes", "", ""));

        assertEquals("answer" + NL, printed());
    }

    @Test
    void writeOutputDoesNothingWithoutAnOutputFile() {
        output.writeOutput(response("answer", "", "", ""), context());

        assertTrue(fileWriter.writes.isEmpty());
    }

    @Test
    void writeOutputOverwritesTheFileWithTheRedactedResponseByDefault() {
        output.writeOutput(response("secret answer", "", "", ""), context("PromptHandlerOutputOutputFile", "answer.md"));

        assertEquals(List.of(new Write("write", path("answer.md"), "[redacted] answer")), fileWriter.writes);
    }

    @Test
    void writeOutputAppendsWhenRequested() {
        output.writeOutput(response("answer", "", "", ""),
                context("PromptHandlerOutputOutputFile", "answer.md", "PromptHandlerOutputAppendToOutputFile", "true"));

        assertEquals(List.of(new Write("append", path("answer.md"), "answer")), fileWriter.writes);
    }

    @Test
    void writeOutputWritesEmptyTextWhenSanitizerReturnsNull() {
        output.writeOutput(response("NULL", "", "", ""), context("PromptHandlerOutputOutputFile", "answer.md"));

        assertEquals(List.of(new Write("write", path("answer.md"), "")), fileWriter.writes);
    }

    @Test
    void writeOutputPropagatesWriteFailures() {
        fileWriter.failFor("answer.md");

        assertThrows(IllegalStateException.class,
                () -> output.writeOutput(response("answer", "", "", ""), context("PromptHandlerOutputOutputFile", "answer.md")));
    }

    @Test
    void writeOutputWithoutContextUsesConfiguredFileAndDirectory() {
        TestConfigUtil.registerConfig(Map.of("sb.output.file", "configured.md", "sb.output.directory", "configured-dir", "sb.output.appendFile", "true"));

        output.writeOutput(response("answer", "", "", ""));

        assertEquals(List.of(new Write("append", Paths.get("configured-dir", "configured.md").toString(), "answer")), fileWriter.writes);
    }

    @Test
    void writeOutputDefaultsToTheCurrentDirectory() {
        output.writeOutput(response("answer", "", "", ""), Map.of("PromptHandlerOutputOutputFile", "answer.md"));

        assertEquals(Paths.get(".", "answer.md").toString(), fileWriter.writes.getFirst().path());
    }

    @Test
    void writeAnnotationsCombinesRedactedAnnotationsAndDebugInfo() {
        output.writeAnnotations(response("answer", "secret notes;", "", "debug"), context("PromptHandlerOutputAnnotationsFile", "notes.md"));

        assertEquals(List.of(new Write("write", path("notes.md"), "[redacted] notes;debug")), fileWriter.writes);
    }

    @Test
    void writeAnnotationsSkipsWhenNoFileOrNoContent() {
        output.writeAnnotations(response("answer", "notes", "", "debug"), context());
        output.writeAnnotations(response("answer", " ", "", " "), context("PromptHandlerOutputAnnotationsFile", "notes.md"));
        output.writeAnnotations(response("answer", "NU", "", "LL"), context("PromptHandlerOutputAnnotationsFile", "notes.md"));

        assertTrue(fileWriter.writes.isEmpty());
    }

    @Test
    void writeAnnotationsLogsWriteFailuresInsteadOfThrowing() {
        fileWriter.failFor("notes.md");

        output.writeAnnotations(response("answer", "notes", "", ""), context("PromptHandlerOutputAnnotationsFile", "notes.md"));

        assertEquals(List.of("Failed to write annotations to file: disk full"), severeMessages());
    }

    @Test
    void writeLinksPrefixesTheLinksHeading() {
        output.writeLinks(response("answer", "", "* a\n* b", ""), context("PromptHandlerOutputLinksFile", "links.md"));

        assertEquals(List.of(new Write("write", path("links.md"), "Links:" + NL + "* a\n* b")), fileWriter.writes);
    }

    @Test
    void writeLinksSkipsWhenNoFileOrNoLinks() {
        output.writeLinks(response("answer", "", "* a", ""), context());
        output.writeLinks(response("answer", "", " ", ""), context("PromptHandlerOutputLinksFile", "links.md"));

        assertTrue(fileWriter.writes.isEmpty());
    }

    @Test
    void writeLinksLogsWriteFailuresInsteadOfThrowing() {
        fileWriter.failFor("links.md");

        output.writeLinks(response("answer", "", "* a", ""), context("PromptHandlerOutputLinksFile", "links.md"));

        assertEquals(List.of("Failed to write links to file: disk full"), severeMessages());
    }

    @Test
    void writeDebugWritesDebugInfoVerbatim() {
        output.writeDebug(response("answer", "", "", "secret debug"), context("PromptHandlerOutputDebugFile", "debug.md"));

        // Debug output is not redacted
        assertEquals(List.of(new Write("write", path("debug.md"), "secret debug")), fileWriter.writes);
    }

    @Test
    void writeDebugSkipsWhenNoFileOrNoDebugInfo() {
        output.writeDebug(response("answer", "", "", "debug"), context());
        output.writeDebug(response("answer", "", "", ""), context("PromptHandlerOutputDebugFile", "debug.md"));

        assertTrue(fileWriter.writes.isEmpty());
    }

    @Test
    void writeDebugLogsWriteFailuresInsteadOfThrowing() {
        fileWriter.failFor("debug.md");

        output.writeDebug(response("answer", "", "", "debug"), context("PromptHandlerOutputDebugFile", "debug.md"));

        assertEquals(List.of("Failed to write debug to file: disk full"), severeMessages());
    }

    @Test
    void fileOutputsWithoutContextUseConfiguredFiles() {
        TestConfigUtil.registerConfig(Map.of(
                "sb.output.directory", "cfg",
                "sb.output.annotationsFile", "a.md",
                "sb.output.linksFile", "l.md",
                "sb.output.debugFile", "d.md"));
        final PromptResponseSimple content = response("answer", "notes", "* link", "debug");

        output.writeAnnotations(content);
        output.writeLinks(content);
        output.writeDebug(content);

        assertEquals(List.of(
                new Write("write", Paths.get("cfg", "a.md").toString(), "notesdebug"),
                new Write("write", Paths.get("cfg", "l.md").toString(), "Links:" + NL + "* link"),
                new Write("write", Paths.get("cfg", "d.md").toString(), "debug")), fileWriter.writes);
    }

    @Test
    @SuppressWarnings("NullAway")
    void saveMetadataWritesEachNamedMetadataSetAsJson() {
        final MetaObjectResults rating = new MetaObjectResults(List.of(new MetaObjectResult("Rating", 7, "id1", "Tool")), "rating.json", "id1");
        final MetaObjectResults unnamed = new MetaObjectResults(List.of(new MetaObjectResult("Ignored", 1)), " ", "id2");
        final List<MetaObjectResults> metadata = new ArrayList<>(Arrays.asList(rating, null, unnamed));

        output.saveMetadata(new PromptResponseSimple("answer", "", "", "", metadata, List.of()), context());

        assertEquals(1, fileWriter.writes.size());
        assertEquals(path("rating.json"), fileWriter.writes.getFirst().path());
        final String json = fileWriter.writes.getFirst().content();
        assertTrue(json.contains("\"name\":\"Rating\""), json);
        assertTrue(json.contains("\"value\":7"), json);
        assertFalse(json.contains("Ignored"), json);
    }

    @Test
    void saveMetadataLogsWriteFailuresAndContinues() {
        fileWriter.failFor("first.json");
        final List<MetaObjectResults> metadata = List.of(
                new MetaObjectResults(List.of(new MetaObjectResult("A", 1)), "first.json", "1"),
                new MetaObjectResults(List.of(new MetaObjectResult("B", 2)), "second.json", "2"));

        output.saveMetadata(new PromptResponseSimple("answer", "", "", "", metadata, List.of()));

        assertEquals(List.of(Paths.get(".", "second.json").toString()), fileWriter.writes.stream().map(Write::path).toList());
        assertEquals(List.of("Failed to write metadata to files: disk full"), severeMessages());
    }

    @Test
    @SuppressWarnings("NullAway")
    void saveIntermediateResultsWritesRedactedContentForValidResults() {
        final List<IntermediateResult> results = new ArrayList<>(Arrays.asList(
                new IntermediateResult("secret step", "step1.txt"),
                null,
                new IntermediateResult("no name", " "),
                new IntermediateResult(null, "no-content.txt"),
                new IntermediateResult("NULL", "nulled.txt")));

        output.saveIntermediateResults(new PromptResponseSimple("answer", "", "", "", List.of(), results), context());

        assertEquals(List.of(
                new Write("write", path("step1.txt"), "[redacted] step"),
                new Write("write", path("nulled.txt"), "")), fileWriter.writes);
    }

    @Test
    void saveIntermediateResultsLogsWriteFailuresAndContinues() {
        fileWriter.failFor("bad.txt");
        final List<IntermediateResult> results = List.of(new IntermediateResult("x", "bad.txt"), new IntermediateResult("y", "good.txt"));

        output.saveIntermediateResults(new PromptResponseSimple("answer", "", "", "", List.of(), results));

        assertEquals(List.of(new Write("write", Paths.get(".", "good.txt").toString(), "y")), fileWriter.writes);
        assertEquals(List.of("Failed to write intermediate result to files: disk full"), severeMessages());
    }

    private List<String> severeMessages() {
        return severeLogs.stream().map(LogRecord::getMessage).toList();
    }

    private record Write(String mode, String path, String content) {
    }

    /**
     * Records every write and append, and fails for any file name registered with failFor.
     */
    private static final class RecordingFileWriter implements FileWriter {
        final List<Write> writes = new CopyOnWriteArrayList<>();
        private final Set<String> failingFiles = new HashSet<>();

        void failFor(final String fileName) {
            failingFiles.add(fileName);
        }

        private void record(final String mode, final Path path, final String content) {
            if (failingFiles.contains(path.getFileName().toString())) {
                throw new IllegalStateException("disk full");
            }
            writes.add(new Write(mode, path.toString(), content));
        }

        @Override
        public void write(final String path, final String content) {
            record("write", Paths.get(path), content);
        }

        @Override
        public void write(final Path path, final String content) {
            record("write", path, content);
        }

        @Override
        public void append(final String path, final String content) {
            record("append", Paths.get(path), content);
        }

        @Override
        public void append(final Path path, final String content) {
            record("append", path, content);
        }
    }
}
