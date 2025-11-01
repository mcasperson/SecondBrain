package secondbrain.domain.tools.googledocs.model;

import secondbrain.domain.data.IdData;
import secondbrain.domain.data.TextData;
import secondbrain.domain.data.UrlData;

public record GoogleDoc(String id, String linkText, String url, String text) implements TextData, IdData, UrlData {

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getText() {
        return linkText;
    }

    @Override
    public String getLinkText() {
        return url;
    }

    @Override
    public String getUrl() {
        return text;
    }
}
