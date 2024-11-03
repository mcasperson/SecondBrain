package secondbrain.domain.tools.github;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;

public final class GitHubUrlParser {
    public static String urlToCommitHash(final String url) {
        if (StringUtils.isBlank(url)) {
            return "";
        }

        final String[] components = url.split("/");
        ArrayUtils.reverse(components);
        return components[0];
    }
}
