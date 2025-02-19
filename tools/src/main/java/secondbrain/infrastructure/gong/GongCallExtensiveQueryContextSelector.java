package secondbrain.infrastructure.gong;

import java.util.List;

public record GongCallExtensiveQueryContextSelector(String context, List<String> contextTiming) {
}
