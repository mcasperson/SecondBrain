package secondbrain.domain.validate;


import java.util.function.Function;

public interface ValidateString {

    String throwIfBlank(String value);

    <T> T throwIfBlank(T source, Function<T, String> getContext);

    boolean isBlank(String value);

    boolean isNotBlank(String value);

    <T> boolean isBlank(T source, Function<T, String> getContext);

    <T> boolean isNotBlank(T source, Function<T, String> getContext);
}
