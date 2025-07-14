package secondbrain.domain.tools.gong.model;

import org.jspecify.annotations.Nullable;
import secondbrain.infrastructure.gong.api.GongCallExtensiveParty;

import java.util.List;

/**
 * This class represents the contract between a tool and the Gong API.
 *
 * @param id      The call ID
 * @param url     The call URL
 * @param parties The list of parties involved in the call
 */
public record GongCallDetails(String id, String url, List<GongCallExtensiveParty> parties) {
    @Nullable
    public GongCallExtensiveParty getPartyFromId(final String speakerId) {
        if (parties == null || parties.isEmpty()) {
            return null;
        }
        return parties.stream()
                .filter(party -> party.speakerId().equals(speakerId))
                .findFirst()
                .orElse(null);
    }

    public String getPartyNameFromId(final String partyId) {
        final GongCallExtensiveParty party = getPartyFromId(partyId);
        return party != null ? party.name() : "Unknown Speaker";
    }
}
