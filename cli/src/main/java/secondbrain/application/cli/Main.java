package secondbrain.application.cli;

import org.apache.commons.lang3.StringUtils;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import secondbrain.Marker;
import secondbrain.domain.handler.PromptHandler;

import java.util.Map;

public class Main {
    public static void main(final String[] args) {
        final Weld weld = new Weld();
        try (WeldContainer weldContainer = weld.addPackages(true, Marker.class).initialize()) {
            final String response = weldContainer.select(PromptHandler.class).get()
                    .handlePrompt(Map.of(), getPrompt(args));
            System.out.println(response);
        }
    }

    private static String getPrompt(final String[] args) {
        if (args.length > 0 && !StringUtils.isBlank(args[0])) {
            System.err.println("Prompt: " + args[0]);
            return args[0];
        }

        throw new RuntimeException("No prompt specified");
    }
}
