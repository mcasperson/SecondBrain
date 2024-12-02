package secondbrain.domain.list;

import java.util.List;

public final class ListUtilsEx {
    static public <T> List<T> safeSubList(final List<T> list, final int fromIndex, final int toIndex) {
        if (list == null || fromIndex >= list.size() || toIndex <= 0 || fromIndex >= toIndex) {
            return List.of();
        }

        return list.subList(Math.max(0, fromIndex), Math.min(list.size(), toIndex));
    }
}
