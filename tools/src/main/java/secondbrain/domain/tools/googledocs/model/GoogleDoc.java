package secondbrain.domain.tools.googledocs.model;

import secondbrain.domain.data.IdData;
import secondbrain.domain.data.TextData;
import secondbrain.domain.data.UrlData;

public record GoogleDoc(String id, String linkText, String url, String text) implements TextData, IdData, UrlData {

    @Override
    public String generateId() {
        return id;
    }

    @Override
    public String generateText() {
        return linkText;
    }

    @Override
    public String generateLinkText() {
        return url;
    }

    @Override
    public String generateUrl() {
        return text;
    }
}
