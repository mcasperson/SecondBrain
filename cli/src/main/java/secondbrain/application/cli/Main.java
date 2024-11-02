package secondbrain.application.cli;

import dev.feedforward.markdownto.DownParser;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import secondbrain.Marker;
import secondbrain.domain.handler.PromptHandler;

import java.util.Map;


public class Main {
    @Inject
    private PromptHandler promptHandler;

    public static void main(final String[] args) {
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
        if (args.length > 0 && !StringUtils.isBlank(args[0])) {
            System.err.println("Prompt: " + args[0]);
            return args[0];
        }

        throw new RuntimeException("No prompt specified");
    }

    private void printMarkDn(final String response) {
        final String markdn = new DownParser(response, true).toSlack().toString();
        System.out.println(markdn);
    }

    public void entry(String[] args) {
        final String response = promptHandler.handlePrompt(Map.of(), getPrompt(args));

        if (args.length > 1 && "markdn".equals(args[1])) {
            printMarkDn(response);
        } else {
            System.out.println(response);
        }
    }
}
