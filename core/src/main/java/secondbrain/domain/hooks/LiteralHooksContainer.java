package secondbrain.domain.hooks;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.apache.commons.lang.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A container for hooks that can be retrieved by comma separated names.
 */
@ApplicationScoped
public class LiteralHooksContainer implements HooksContainer {
    @Inject
    private Instance<PreprocessingHook> preprocessingHooks;

    @Override
    public List<PreprocessingHook> getMatchingHooks(final String name) {
        if (StringUtils.isBlank(name) || preprocessingHooks == null) {
            return List.of();
        }

        return getHookNames(name).stream()
                .map(this::getHookByName)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }

    public Optional<PreprocessingHook> getHookByName(final String name) {
        if (StringUtils.isBlank(name) || preprocessingHooks == null) {
            return Optional.empty();
        }

        return preprocessingHooks.stream()
                .filter(hook -> hook.getName().equals(name))
                .findFirst();
    }

    public List<String> getHookNames(final String name) {
        if (StringUtils.isBlank(name)) {
            return List.of();
        }

        return Stream.of(name.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
    }
}
