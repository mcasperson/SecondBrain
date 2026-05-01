package secondbrain.application.cli;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.WeldInitiator;
import org.jboss.weld.junit5.WeldJunit5Extension;
import org.jboss.weld.junit5.WeldSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import secondbrain.domain.converter.StringConverter;
import secondbrain.domain.converter.StringConverterSelector;
import secondbrain.domain.files.FileWriter;
import secondbrain.domain.files.PathBuilder;
import secondbrain.domain.handler.PromptHandler;
import secondbrain.domain.handler.PromptHandlerResponse;
import secondbrain.domain.handler.PromptResponseSimple;
import secondbrain.domain.json.JsonDeserializer;
import secondbrain.domain.persist.LocalStorageReadWrite;
import secondbrain.domain.sanitize.FinancialLocationContactRedaction;
import secondbrain.domain.toolbuilder.ToolSelector;
import secondbrain.domain.tooldefs.IntermediateResult;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests that Main correctly sanitizes PII before writing to any files.
 *
 * <p>Uses an explicit Weld CDI container (discovery disabled via
 * {@link WeldInitiator#createWeld()}) so that only the three registered bean
 * classes are present: {@link Main}, {@link FinancialLocationContactRedaction},
 * and {@link MockBeans}.  All other dependencies of {@link Main} are satisfied
 * by Mockito mocks produced by {@link MockBeans}.
 *
 * <p>The real {@link FinancialLocationContactRedaction} is wired in so that the
 * PII-sanitization logic is exercised end-to-end rather than stubbed away.
 *
 * <p>Config properties are read from {@code META-INF/microprofile-config.properties}
 * in test resources so that {@code @ConfigProperty} injection into {@link Main} is
 * satisfied before the CDI container starts injecting beans.
 */
@ExtendWith(WeldJunit5Extension.class)
@SuppressWarnings("NullAway")
class MainTest {

    private static final String PII_EMAIL = "john.doe@example.com";
    private static final String PII_PHONE = "555-867-5309";
    private static final String PII_CREDIT_CARD = "4111111111111111";
    private static final String PII_ANNOTATION_EMAIL = "jane.smith@company.org";

    /**
     * Explicit Weld container with discovery disabled.  Only the three listed
     * bean classes are registered; everything else is supplied as mocks via
     * {@link MockBeans}'.  The SmallRye {@link ConfigExtension} is added so that
     * {@code @ConfigProperty} injection in {@link Main} is resolved from the
     * {@code META-INF/microprofile-config.properties} test resource.
     */
    @WeldSetup
    private final WeldInitiator weld = WeldInitiator
            .from(WeldInitiator.createWeld()
                    .beanClasses(Main.class, FinancialLocationContactRedaction.class, MockBeans.class)
                    .addExtension(new ConfigExtension()))
            .activate(ApplicationScoped.class)
            .build();

    /**
     * CDI-managed Main instance with dependencies injected from the container.
     */
    @Inject
    private Main main;

    @BeforeEach
    void resetMocks() {
        // Reset all Mockito mock state between tests to avoid interaction bleed-over.
        reset(
                MockBeans.promptHandlerMock,
                MockBeans.fileWriterMock,
                MockBeans.pathBuilderMock,
                MockBeans.localStorageMock,
                MockBeans.jsonDeserializerMock);

        // Stub the path builder to simply concatenate directory + filename.
        when(MockBeans.pathBuilderMock.getFilePath(any(String.class), any(String.class)))
                .thenAnswer(inv -> Path.of(
                        inv.getArgument(0, String.class),
                        inv.getArgument(1, String.class)));
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void testNoPiiWrittenToOutputFile() {
        when(MockBeans.promptHandlerMock.handlePrompt(any(), any()))
                .thenReturn(buildPiiResponse());

        main.entry(new String[]{"Tell me about the customer"});

        final ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(MockBeans.fileWriterMock, atLeastOnce()).write(any(Path.class), captor.capture());

        for (final String written : captor.getAllValues()) {
            assertFalse(written.contains(PII_EMAIL),
                    "Email PII must not appear in any file write; found in: " + written);
            assertFalse(written.contains(PII_CREDIT_CARD),
                    "Credit-card PII must not appear in any file write; found in: " + written);
        }
    }

    @Test
    void testNoPiiWrittenToAnnotationsFile() {
        when(MockBeans.promptHandlerMock.handlePrompt(any(), any()))
                .thenReturn(buildPiiResponse());

        main.entry(new String[]{"Tell me about the customer"});

        final ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(MockBeans.fileWriterMock, atLeastOnce()).write(any(Path.class), captor.capture());

        for (final String written : captor.getAllValues()) {
            assertFalse(written.contains(PII_ANNOTATION_EMAIL),
                    "Annotation email PII must not appear in any file write; found in: " + written);
        }
    }

    @Test
    void testNoPiiWrittenToIntermediateResultFiles() {
        // Intermediate results are always written regardless of file/annotationsFile config,
        // so no config override is needed – the write will be captured below.
        when(MockBeans.promptHandlerMock.handlePrompt(any(), any()))
                .thenReturn(buildPiiResponse());

        main.entry(new String[]{"Tell me about the customer"});

        final ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(MockBeans.fileWriterMock, atLeastOnce()).write(any(Path.class), captor.capture());

        for (final String written : captor.getAllValues()) {
            assertFalse(written.contains(PII_CREDIT_CARD),
                    "Credit-card PII must not appear in intermediate result file; found in: " + written);
            assertFalse(written.contains(PII_PHONE),
                    "Phone PII must not appear in intermediate result file; found in: " + written);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a {@link PromptHandlerResponse} whose text fields deliberately contain
     * several categories of PII so that tests can verify they are all redacted before
     * being persisted.
     */
    private PromptHandlerResponse buildPiiResponse() {
        final String responseText =
                "Contact " + PII_EMAIL + " or call " + PII_PHONE + " for assistance.";
        final String annotations = "Follow-up address: " + PII_ANNOTATION_EMAIL;
        final String intermediateContent =
                "Payment via card " + PII_CREDIT_CARD + ", phone " + PII_PHONE;

        return new PromptResponseSimple(
                responseText,
                annotations,
                /*links=*/ "",
                /*debug=*/ "",
                List.of(),
                List.of(new IntermediateResult(intermediateContent, "intermediate.txt")));
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

    // -------------------------------------------------------------------------
    // CDI mock producer bean
    // -------------------------------------------------------------------------

    /**
     * CDI producer bean registered with the Weld test container.  Each injection
     * point in {@link Main} is satisfied here.
     * <ul>
     *   <li>Interface types ({@link PromptHandler}, {@link FileWriter}, {@link PathBuilder},
     *       {@link LocalStorageReadWrite}, {@link JsonDeserializer}) use Mockito mocks so that
     *       individual tests can stub and verify them.</li>
     *   <li>Concrete CDI classes ({@link ToolSelector}, {@link StringConverterSelector}) are
     *       replaced by minimal anonymous stubs to avoid Mockito inline-mock limitations on
     *       Java 25; neither class is exercised in these tests.</li>
     * </ul>
     * Static mock instances are used so that test methods can reference them as
     * {@code MockBeans.fileWriterMock}.  All Mockito mocks are reset before each
     * test in {@link MainTest#resetMocks()}.
     */
    @ApplicationScoped
    static class MockBeans {

        // ---- Mockito mocks (interfaces) ----
        static final PromptHandler promptHandlerMock = mock(PromptHandler.class);
        static final FileWriter fileWriterMock = mock(FileWriter.class);
        static final PathBuilder pathBuilderMock = mock(PathBuilder.class);
        static final LocalStorageReadWrite localStorageMock = mock(LocalStorageReadWrite.class);
        static final JsonDeserializer jsonDeserializerMock = mock(JsonDeserializer.class);

        // ---- Plain stubs (concrete CDI classes not directly exercised in tests) ----

        /**
         * Stub selector that always returns a no-op converter; never verified.
         */
        static final StringConverterSelector stringConverterSelectorStub =
                new StringConverterSelector() {
                    @Override
                    public StringConverter getStringConverter(final String format) {
                        return noOpConverter();
                    }
                };

        /**
         * Stub selector that exposes no tools; only reached via {@code --help}.
         */
        static final ToolSelector toolSelectorStub = new ToolSelector() {
            @Override
            public java.util.List<secondbrain.domain.tooldefs.Tool<?>> getAvailableTools() {
                return java.util.List.of();
            }
        };

        @Produces
        public PromptHandler promptHandler() {
            return promptHandlerMock;
        }

        @Produces
        public FileWriter fileWriter() {
            return fileWriterMock;
        }

        @Produces
        public PathBuilder pathBuilder() {
            return pathBuilderMock;
        }

        @Produces
        public LocalStorageReadWrite localStorageReadWrite() {
            return localStorageMock;
        }

        @Produces
        public JsonDeserializer jsonDeserializer() {
            return jsonDeserializerMock;
        }

        @Produces
        public StringConverterSelector stringConverterSelector() {
            return stringConverterSelectorStub;
        }

        @Produces
        public ToolSelector toolSelector() {
            return toolSelectorStub;
        }

        /**
         * Produces a named {@link Logger} for any injection point, mirroring the
         * behaviour of {@code secondbrain.domain.logger.Loggers} without requiring
         * file-system access.
         */
        @Produces
        public Logger logger(final InjectionPoint ip) {
            return Logger.getLogger(ip.getMember().getDeclaringClass().getName());
        }
    }

}

