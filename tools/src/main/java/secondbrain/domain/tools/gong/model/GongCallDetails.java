package secondbrain.domain.tools.gong.model;

import org.jspecify.annotations.Nullable;
import secondbrain.infrastructure.gong.api.GongCallExtensiveParty;

import java.util.List;
import java.util.Objects;

/**
 * This class represents the contract between a tool and the Gong API.
 *
 * @param id      The call ID
 * @param url     The call URL
 * @param parties The list of parties involved in the call
 */
public record GongCallDetails(String id, String url, String company, List<GongCallExtensiveParty> parties) {
    @Nullable
    public GongCallExtensiveParty getPartyFromId(final String speakerId) {
        if (parties == null || parties.isEmpty() || speakerId == null || speakerId.isBlank()) {
            return null;
        }

        return parties.stream()
                .filter(Objects::nonNull)
                .filter(party -> speakerId.equals(party.speakerId()))
                .findFirst()
                .orElse(null);
    }

    public String getPartyNameFromId(final String partyId) {
        final GongCallExtensiveParty party = getPartyFromId(partyId);
        return party != null ? party.name() : "Unknown Speaker";
    }
}
