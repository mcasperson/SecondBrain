package secondbrain.application.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import secondbrain.domain.converter.StringConverter;
import secondbrain.domain.converter.StringConverterSelector;
import secondbrain.domain.files.FileWriter;
import secondbrain.domain.files.PathBuilder;
import secondbrain.domain.handler.PromptHandler;
import secondbrain.domain.handler.PromptHandlerResponse;
import secondbrain.domain.json.JsonDeserializer;
import secondbrain.domain.persist.LocalStorageReadWrite;
import secondbrain.domain.sanitize.FinancialLocationContactRedaction;
import secondbrain.domain.toolbuilder.ToolSelector;
import secondbrain.domain.tooldefs.IntermediateResult;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.handler.PromptResponseSimple;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that Main correctly sanitizes PII before writing to any files.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("NullAway")
class MainTest {

    private static final String PII_EMAIL = "john.doe@example.com";
    private static final String PII_PHONE = "555-867-5309";
    private static final String PII_CREDIT_CARD = "4111111111111111";
    private static final String PII_ANNOTATION_EMAIL = "jane.smith@company.org";

    @Mock
    private PromptHandler promptHandler;

    /**
     * Stub selector that always returns the no-op converter without requiring CDI injection.
     */
    private final StringConverterSelector converterSelector = new StringConverterSelector() {
        @Override
        public StringConverter getStringConverter(final String format) {
            return noOpConverter();
        }
    };

    /**
     * Stub ToolSelector that returns an empty list; only called when --help is requested.
     */
    private final ToolSelector stubToolSelector = new ToolSelector() {
        @Override
        public List<Tool<?>> getAvailableTools() {
            return List.of();
        }
    };

    @Mock
    private JsonDeserializer jsonDeserializer;

    @Mock
    private PathBuilder pathBuilder;

    @Mock
    private FileWriter fileWriter;

    @Mock
    private LocalStorageReadWrite localStorageReadWrite;

    @InjectMocks
    private Main main;

    /** Real sanitizer so we can verify it actually strips PII from what's written to files. */
    private final FinancialLocationContactRedaction sanitizeDocument = new FinancialLocationContactRedaction();

    @BeforeEach
    void setUp() throws Exception {
        final Method construct = FinancialLocationContactRedaction.class.getDeclaredMethod("construct");
        construct.setAccessible(true);
        construct.invoke(sanitizeDocument);

        setField(main, "sanitizeDocument", sanitizeDocument);
        setField(main, "stringConverterSelector", converterSelector);
        setField(main, "toolSelector", stubToolSelector);
        setField(main, "logger", Logger.getLogger(MainTest.class.getName()));
        setField(main, "promptFile", Optional.empty());
        setField(main, "file", Optional.of("output.txt"));
        setField(main, "appendToOutputFile", Boolean.FALSE);
        setField(main, "annotationsFile", Optional.of("annotations.txt"));
        setField(main, "linksFile", Optional.empty());
        setField(main, "debugFile", Optional.empty());
        setField(main, "printAnnotations", Boolean.FALSE);
        setField(main, "directory", ".");
    }

    @Test
    void testNoPiiWrittenToOutputFile() {
        final PromptHandlerResponse response = buildPiiResponse();

        when(promptHandler.handlePrompt(any(), any())).thenReturn(response);
        when(pathBuilder.getFilePath(any(), any()))
                .thenAnswer(inv -> Path.of(inv.getArgument(0).toString(), inv.getArgument(1).toString()));

        main.entry(new String[]{"Tell me about the customer"});

        final ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(fileWriter, atLeastOnce()).write(any(Path.class), contentCaptor.capture());

        for (final String written : contentCaptor.getAllValues()) {
            assertFalse(written.contains(PII_EMAIL),
                    "Email PII '" + PII_EMAIL + "' must not appear in file output, but found in: " + written);
            assertFalse(written.contains(PII_CREDIT_CARD),
                    "Credit card PII '" + PII_CREDIT_CARD + "' must not appear in file output, but found in: " + written);
        }
    }

    @Test
    void testNoPiiWrittenToAnnotationsFile() {
        final PromptHandlerResponse response = buildPiiResponse();

        when(promptHandler.handlePrompt(any(), any())).thenReturn(response);
        when(pathBuilder.getFilePath(any(), any()))
                .thenAnswer(inv -> Path.of(inv.getArgument(0).toString(), inv.getArgument(1).toString()));

        main.entry(new String[]{"Tell me about the customer"});

        final ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(fileWriter, atLeastOnce()).write(any(Path.class), contentCaptor.capture());

        // Annotations file will be one of the captured writes; ensure none contain annotation PII
        for (final String written : contentCaptor.getAllValues()) {
            assertFalse(written.contains(PII_ANNOTATION_EMAIL),
                    "Email PII '" + PII_ANNOTATION_EMAIL + "' must not appear in annotations file, but found in: " + written);
        }
    }

    @Test
    void testNoPiiWrittenToIntermediateResultFiles() throws Exception {
        // Override: use an intermediate result file so saveIntermediateResults is exercised
        setField(main, "file", Optional.empty());
        setField(main, "annotationsFile", Optional.empty());

        final PromptHandlerResponse response = buildPiiResponse();

        when(promptHandler.handlePrompt(any(), any())).thenReturn(response);
        when(pathBuilder.getFilePath(any(), any()))
                .thenAnswer(inv -> Path.of(inv.getArgument(0).toString(), inv.getArgument(1).toString()));

        main.entry(new String[]{"Tell me about the customer"});

        final ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(fileWriter, atLeastOnce()).write(any(Path.class), contentCaptor.capture());

        for (final String written : contentCaptor.getAllValues()) {
            assertFalse(written.contains(PII_CREDIT_CARD),
                    "Credit card PII '" + PII_CREDIT_CARD + "' must not appear in intermediate result file, but found in: " + written);
            assertFalse(written.contains(PII_PHONE),
                    "Phone PII '" + PII_PHONE + "' must not appear in intermediate result file, but found in: " + written);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a PromptHandlerResponse whose text fields contain several PII values.
     */
    private PromptHandlerResponse buildPiiResponse() {
        final String responseText = "Contact " + PII_EMAIL + " or call " + PII_PHONE + " for assistance.";
        final String annotations = "Follow-up address: " + PII_ANNOTATION_EMAIL;
        final String intermediateContent = "Payment via card " + PII_CREDIT_CARD + ", phone " + PII_PHONE;

        return new PromptResponseSimple(
                responseText,
                annotations,
                /*links=*/ "",
                /*debug=*/ "",
                List.of(),
                List.of(new IntermediateResult(intermediateContent, "intermediate.txt"))
        );
    }

    private static StringConverter noOpConverter() {
        return new StringConverter() {
            @Override
            public String getFormat() {
                return "no-op";
            }

            @Override
            public String convert(final String response) {
                return response;
            }
        };
    }

    private static void setField(final Object target, final String fieldName, final Object value) throws Exception {
        final Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}

