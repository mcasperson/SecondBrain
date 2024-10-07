package secondbrain.application.cli;

import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import secondbrain.domain.handler.PromptHandler;
import secondbrain.domain.logging.LogConfig;

public class Main {
    public static void main(final String[] args) {
        LogConfig.init();
        final Weld weld = new Weld();
        try (WeldContainer weldContainer = weld.initialize()) {
            final String response = weldContainer.select(PromptHandler.class).get()
                    .handlePrompt("Display the greeting \"Hi World!\"");
            System.out.println(response);
        }
    }
}
