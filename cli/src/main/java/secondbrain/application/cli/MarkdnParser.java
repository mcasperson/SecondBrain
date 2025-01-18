package secondbrain.application.cli;

import dev.feedforward.markdownto.DownParser;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class MarkdnParser {
    public String printMarkDn(final String response) {
        final String markdn = new DownParser(response, true).toSlack().toString();
        // The markdown parser seems to let a bunch of strong text through, so clean that up manually
        final String fixedMarkdn = markdn.replaceAll("\\*\\*(.+)\\*\\*", "*$1*");
        return fixedMarkdn;
    }
}
