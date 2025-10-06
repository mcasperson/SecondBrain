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
    private Instance<PreProcessingHook> preprocessingHooks;

    @Inject
    private Instance<PostInferenceHook> postInferenceHooks;

    @Override
    public List<PreProcessingHook> getMatchingPreProcessorHooks(final String name) {
        if (StringUtils.isBlank(name) || preprocessingHooks == null) {
            return List.of();
        }

        return getHookNames(name).stream()
                .map(this::getPreProcessorHookByName)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }

    @Override
    public List<PostInferenceHook> getMatchingPostInferenceHooks(final String name) {
        if (StringUtils.isBlank(name) || postInferenceHooks == null) {
            return List.of();
        }

        return getHookNames(name).stream()
                .map(this::getPostInferenceHookByName)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }

    public Optional<PreProcessingHook> getPreProcessorHookByName(final String name) {
        if (StringUtils.isBlank(name) || preprocessingHooks == null) {
            return Optional.empty();
        }

        return preprocessingHooks.stream()
                .filter(hook -> hook.getName().equals(name))
                .findFirst();
    }

    public Optional<PostInferenceHook> getPostInferenceHookByName(final String name) {
        if (StringUtils.isBlank(name) || postInferenceHooks == null) {
            return Optional.empty();
        }

        return postInferenceHooks.stream()
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
