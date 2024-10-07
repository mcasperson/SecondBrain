package secondbrain.application.cli;

import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import secondbrain.domain.handler.PromptHandler;
import domain.logging.LogConfig;

public class Main {
    private static final String DEFAULT_PROMPT = "Summarize the last 30 messages from the slack channel topic-features";

    public static void main(final String[] args) {
        LogConfig.init();
        final Weld weld = new Weld();
        try (WeldContainer weldContainer = weld.initialize()) {
            final String response = weldContainer.select(PromptHandler.class).get()
                    .handlePrompt(DEFAULT_PROMPT);
            System.out.println(response);
        }
    }
}
