package secondbrain.domain.tools.publicfile.model;

import secondbrain.domain.data.IdData;
import secondbrain.domain.data.TextData;
import secondbrain.domain.data.UrlData;

import java.util.Objects;

public record FileContents(String id, String url, String text) implements TextData, IdData, UrlData {
    @Override
    public String getText() {
        return Objects.requireNonNullElse(text, "");
    }

    @Override
    public String getId() {
        return Objects.requireNonNullElse(id, "");
    }

    @Override
    public String getLinkText() {
        return "File";
    }

    @Override
    public String getUrl() {
        return Objects.requireNonNullElse(url, "");
    }
}
