package secondbrain.application.cli;

import io.smallrye.common.annotation.Identifier;
import io.vavr.control.Try;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import secondbrain.Marker;
import secondbrain.domain.converter.StringConverter;
import secondbrain.domain.converter.StringConverterSelector;
import secondbrain.domain.files.FileWriter;
import secondbrain.domain.files.PathBuilder;
import secondbrain.domain.handler.PromptHandler;
import secondbrain.domain.handler.PromptHandlerOutput;
import secondbrain.domain.handler.PromptHandlerResponse;
import secondbrain.domain.json.JsonDeserializer;
import secondbrain.domain.persist.LocalStorageReadWrite;
import secondbrain.domain.sanitize.SanitizeDocument;
import secondbrain.domain.toolbuilder.ToolSelector;

import java.nio.file.Files;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;


public class Main {
    @Inject
    private PromptHandler promptHandler;

    @Inject
    private StringConverterSelector stringConverterSelector;

    @Inject
    @ConfigProperty(name = "sb.input.file")
    private Optional<String> promptFile;

    @Inject
    @ConfigProperty(name = "sb.output.directory", defaultValue = ".")
    private String directory;

    @Inject
    private PathBuilder pathBuilder;

    @Inject
    private ToolSelector toolSelector;

    @Inject
    private Logger logger;

    @Inject
    private PromptHandlerOutput promptHandlerOutput;


    /**
     * This is included here to force the service to initialise as early as possible.
     */
    @Inject
    private LocalStorageReadWrite localStorageReadWrite;

    public static void main(final String[] args) {
        // Remove some of the initial SLF4J logging noise
        System.setProperty("slf4j.internal.verbosity", "WARN");

        // Disable Netty unsafe operations for better compatibility
        // See https://netty.io/wiki/java-24-and-sun.misc.unsafe.html
        System.setProperty("io.netty.noUnsafe", "true");

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
        if (promptFile.isPresent() && StringUtils.isNotBlank(promptFile.get())) {
            return Try.of(() -> Files.readString(pathBuilder.getFilePath(directory, promptFile.get())))
                    .onFailure(e -> logger.severe("Failed to read prompt from file: " + e.getMessage()))
                    .get();
        }

        if (args.length > 0 && !StringUtils.isBlank(args[0])) {
            logger.info("Prompt: " + args[0]);
            return args[0];
        }

        throw new RuntimeException("No prompt specified");
    }

    @SuppressWarnings("ReturnValueIgnored")
    public void entry(final String[] args) {
        final String command = args.length > 0 ? args[0] : "";
        if ("--help".equals(command)) {
            printHelp();
            return;
        }

        final String format = args.length > 1 ? args[1] : "no-op";
        final StringConverter converter = stringConverterSelector.getStringConverter(format);

        Try.of(() -> promptHandler.handlePrompt(Map.of(), getPrompt(args)))
                .map(response -> response.updateResponseText(converter))
                .onSuccess(promptHandlerOutput::printOutput)
                .onSuccess(promptHandlerOutput::writeAnnotations)
                .onSuccess(promptHandlerOutput::writeLinks)
                .onSuccess(promptHandlerOutput::writeDebug)
                .onSuccess(promptHandlerOutput::writeOutput)
                .onSuccess(promptHandlerOutput::saveMetadata)
                .onSuccess(promptHandlerOutput::saveIntermediateResults)
                .onFailure(e -> logger.severe("Failed to process prompt: " + e.getMessage()));
    }

    private void printHelp() {
        System.out.println("Force the use of a specific tool with the environment variable SB_TOOLS_FORCE, e.g. 'SB_TOOLS_FORCE=MyTool java -jar sb.jar \"My prompt\"'");
        System.out.println("Available tools:");
        toolSelector.getAvailableTools().stream()
                .map(tool -> tool.getName() + ": " + tool.getDescription())
                .forEach(tool -> System.out.println(" - " + tool));
    }


}
