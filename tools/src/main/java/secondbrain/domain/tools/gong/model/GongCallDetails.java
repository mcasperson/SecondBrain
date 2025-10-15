package secondbrain.domain.tools.gong.model;

import org.jspecify.annotations.Nullable;
import secondbrain.domain.data.IdData;
import secondbrain.domain.data.TextData;
import secondbrain.domain.data.UrlData;
import secondbrain.domain.tooldefs.MetaObjectResult;
import secondbrain.domain.tooldefs.MetaObjectResults;
import secondbrain.infrastructure.gong.api.GongCallExtensiveParty;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * This class represents the contract between a tool and the Gong API.
 *
 * @param id      The call ID
 * @param url     The call URL
 * @param parties The list of parties involved in the call
 */
public record GongCallDetails(String id,
                              String url,
                              String company,
                              List<GongCallExtensiveParty> parties,
                              String transcript,
                              @Nullable MetaObjectResult meta1,
                              @Nullable MetaObjectResult meta2) implements IdData, TextData, UrlData {

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getText() {
        return transcript;
    }

    @Override
    public String getLinkText() {
        return "Gong Call " + getId();
    }

    @Override
    public String getUrl() {
        return url;
    }

    public MetaObjectResults getMetaObjectResults() {
        return new MetaObjectResults(Stream.of(meta1, meta2).filter(Objects::nonNull).toList());
    }
}
