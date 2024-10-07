package secondbrain.application.cli;

import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import secondbrain.domain.handler.PromptHandler;
import domain.logging.LogConfig;

import java.util.Map;
import java.util.stream.Collectors;

public class Main {
    private static final String DEFAULT_PROMPT = "Perform a smoke test";

    public static void main(final String[] args) {
        LogConfig.init();
        final Weld weld = new Weld();
        try (WeldContainer weldContainer = weld.initialize()) {
            final String response = weldContainer.select(PromptHandler.class).get()
                    .handlePrompt(getContext(), getPrompt(args));
            System.out.println(response);
        }
    }

    private static String getPrompt(final String[] args) {
        return args.length > 0 ? args[0] : DEFAULT_PROMPT;
    }

    private static Map<String, String> getContext() {
        return  System.getenv().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("SB_"))
                .map(entry -> Map.entry(entry.getKey().substring(3), entry.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
