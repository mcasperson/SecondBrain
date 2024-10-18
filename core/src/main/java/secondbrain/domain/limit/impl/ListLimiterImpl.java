package secondbrain.domain.limit.impl;

import jakarta.enterprise.context.ApplicationScoped;
import secondbrain.domain.limit.ListLimiter;

import java.util.List;

@ApplicationScoped
public class ListLimiterImpl implements ListLimiter {
    public List<String> limitListContent(final List<String> list, final int limit) {
        if (limit <= 0) {
            return List.of();
        }

        int length = 0;

        for (int i = 0; i < list.size(); i++) {
            length += list.get(i).length();
            if (length > limit) {
                return list.subList(0, i);
            }
        }

        return list;
    }
}
