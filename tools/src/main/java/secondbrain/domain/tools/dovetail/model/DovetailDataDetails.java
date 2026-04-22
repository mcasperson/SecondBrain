package secondbrain.domain.tools.dovetail.model;

import secondbrain.domain.data.IdData;
import secondbrain.domain.data.TextData;
import secondbrain.domain.data.UrlData;

import java.util.Objects;

/**
 * Represents the contract between the Dovetail tool and a single data item exported from Dovetail.
 *
 * @param id             The data item ID
 * @param title          The data item title
 * @param projectTitle   The project title
 * @param createdAt      The creation date/time string
 * @param markdown       The exported markdown content
 * @param dovetailBaseUrl The base URL of the Dovetail instance (e.g. https://octopusdeploy.dovetail.com)
 */
public record DovetailDataDetails(
        String id,
        String title,
        String projectTitle,
        String createdAt,
        String markdown,
        String dovetailBaseUrl) implements IdData, TextData, UrlData {

    @Override
    public String generateId() {
        return Objects.requireNonNullElse(id, "");
    }

    @Override
    public String generateText() {
        return Objects.requireNonNullElse(markdown, "");
    }

    @Override
    public String generateLinkText() {
        return "Dovetail: " + Objects.requireNonNullElse(title, generateId());
    }

    @Override
    public String generateUrl() {
        return dovetailBaseUrl + "/data/" + generateId();
    }
}

