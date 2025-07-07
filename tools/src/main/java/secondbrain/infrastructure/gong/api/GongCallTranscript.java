package secondbrain.infrastructure.gong.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import secondbrain.domain.tools.gong.model.GongCallDetails;

import java.util.List;
import java.util.Optional;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GongCallTranscript(List<GongCallTranscriptCollection> callTranscripts) {
    public String getTranscript(final GongCallDetails call) {
        if (callTranscripts == null) {
            return "";
        }

        return callTranscripts.stream()
                .flatMap(transcript -> transcript.transcript().stream())
                .map(transcriptItem ->
                        Optional.ofNullable(call.getPartyFromId(transcriptItem.speakerId()))
                                .map(GongCallExtensiveParty::name)
                                .orElse("Unknown") +
                                ":\n" +
                                transcriptItem.sentences().
                                        stream()
                                        .map(GongCallTranscriptItemSentence::text)
                                        .reduce("", (a, b) -> a + "\n" + b))
                .reduce("", (a, b) -> a + "\n" + b);
    }
}
