package secondbrain.domain.converter;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;

@ApplicationScoped
public class JSoupHtmlToText implements HtmlToText {
    @Override
    public String getText(final String html) {
        final String parsed = Jsoup.parse(html).text();
        return StringUtils.isBlank(parsed) ? html : parsed;
    }
}
