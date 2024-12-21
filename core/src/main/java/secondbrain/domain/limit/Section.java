package secondbrain.domain.limit;

import java.util.Set;

public record Section(int start, int end, Set<String> keyword) {
}
