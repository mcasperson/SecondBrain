package secondbrain.domain.limit;

import jakarta.enterprise.context.ApplicationScoped;
import secondbrain.domain.context.IndividualContext;

import java.util.List;
import java.util.function.Function;

/**
 * An implementation of ListLimiter that cuts off the list at the first element that would exceed the limit.
 */
@ApplicationScoped
public class ListLimiterAtomicCutOff implements ListLimiter {
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

    @Override
    public <T> List<T> limitListContent(final List<T> list, final Function<T, String> getContext, final int limit) {
        if (limit <= 0) {
            return List.of();
        }

        int length = 0;

        for (int i = 0; i < list.size(); i++) {
            length += getContext.apply(list.get(i)).length();
            if (length > limit) {
                return list.subList(0, i);
            }
        }

        return list;
    }

    @Override
    public <U> List<IndividualContext<String, U>> limitIndividualContextListContent(
            final List<IndividualContext<String, U>> list,
            final int limit) {
        if (limit <= 0) {
            return List.of();
        }

        int length = 0;

        for (int i = 0; i < list.size(); i++) {
            length += list.get(i).context().length();
            if (length > limit) {
                return list.subList(0, i);
            }
        }

        return list;
    }
}
