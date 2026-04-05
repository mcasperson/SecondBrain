package secondbrain.domain.url;

import com.azure.cosmos.implementation.apachecommons.lang.StringUtils;
import io.vavr.control.Try;

import java.net.URI;
import java.util.Arrays;

public class UrlUtils {
    public static String getQueryString(final String url, final String queryString) {
        if (StringUtils.isEmpty(url)) {
            return "";
        }

        if (StringUtils.isEmpty(queryString)) {
            return "";
        }

        return Try.of(() -> new URI(url))
                .map(URI::getQuery)
                .map(query -> Arrays.stream(query.split("&"))
                        .filter(param -> param.startsWith(queryString + "="))
                        .map(param -> param.substring((queryString + "=").length()))
                        .findFirst()
                        .orElse(""))
                .getOrElse("");
    }
}
