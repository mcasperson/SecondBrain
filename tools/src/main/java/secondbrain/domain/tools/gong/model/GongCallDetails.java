package secondbrain.domain.tools.gong.model;

import org.jspecify.annotations.Nullable;
import secondbrain.domain.data.IdData;
import secondbrain.domain.data.TextData;
import secondbrain.domain.data.UrlData;
import secondbrain.domain.tooldefs.MetaObjectResult;
import secondbrain.domain.tooldefs.MetaObjectResults;
import secondbrain.infrastructure.gong.api.GongCallExtensiveParty;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
                              ZonedDateTime started,
                              @Nullable MetaObjectResult meta1,
                              @Nullable MetaObjectResult meta2,
                              @Nullable MetaObjectResult meta3,
                              @Nullable MetaObjectResult meta4,
                              @Nullable MetaObjectResult meta5,
                              @Nullable MetaObjectResult meta6,
                              @Nullable MetaObjectResult meta7,
                              @Nullable MetaObjectResult meta8,
                              @Nullable MetaObjectResult meta9,
                              @Nullable MetaObjectResult meta10) implements IdData, TextData, UrlData {

    @Override
    public String generateId() {
        return id;
    }

    @Override
    public String generateText() {
        return transcript;
    }

    @Override
    public String generateLinkText() {
        return "Gong Call " + generateId();
    }

    @Override
    public String generateUrl() {
        return url;
    }

    public Optional<MetaObjectResult> getMeta1() {
        return Optional.ofNullable(meta1);
    }

    public Optional<MetaObjectResult> getMeta2() {
        return Optional.ofNullable(meta2);
    }

    public Optional<MetaObjectResult> getMeta3() {
        return Optional.ofNullable(meta3);
    }

    public Optional<MetaObjectResult> getMeta4() {
        return Optional.ofNullable(meta4);
    }

    public Optional<MetaObjectResult> getMeta5() {
        return Optional.ofNullable(meta5);
    }

    public Optional<MetaObjectResult> getMeta6() {
        return Optional.ofNullable(meta6);
    }

    public Optional<MetaObjectResult> getMeta7() {
        return Optional.ofNullable(meta7);
    }

    public Optional<MetaObjectResult> getMeta8() {
        return Optional.ofNullable(meta8);
    }

    public Optional<MetaObjectResult> getMeta9() {
        return Optional.ofNullable(meta9);
    }

    public Optional<MetaObjectResult> getMeta10() {
        return Optional.ofNullable(meta10);
    }

    public MetaObjectResults generateMetaObjectResults() {
        return new MetaObjectResults(Stream.of(meta1, meta2, meta3, meta4, meta5, meta6, meta7, meta8, meta9, meta10).filter(Objects::nonNull).toList());
    }

    /**
     * If we don't need to carry the full transcript any more, clear it out to save some memory.
     */
    public GongCallDetails clearTranscript() {
        return new GongCallDetails(id, url, company, parties, "", started, meta1, meta2, meta3, meta4, meta5, meta6, meta7, meta8, meta9, meta10);
    }
}
