package secondbrain.domain.tools.publicfile.model;

import secondbrain.domain.data.IdData;
import secondbrain.domain.data.TextData;
import secondbrain.domain.data.UrlData;

import java.util.Objects;

public record FileContents(String id, String url, String text) implements TextData, IdData, UrlData {
    @Override
    public String generateText() {
        return Objects.requireNonNullElse(text, "");
    }

    @Override
    public String generateId() {
        return Objects.requireNonNullElse(id, "");
    }

    @Override
    public String generateLinkText() {
        return "File";
    }

    @Override
    public String generateUrl() {
        return Objects.requireNonNullElse(url, "");
    }
}
