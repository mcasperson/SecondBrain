package secondbrain.domain.validate;


import java.util.function.Function;

public interface ValidateString {

    String throwIfEmpty(String value);

    <T> T throwIfEmpty(T source, Function<T, String> getContext);

    boolean isEmpty(String value);

    boolean isNotEmpty(String value);

    <T> boolean isEmpty(T source, Function<T, String> getContext);

    <T> boolean isNotEmpty(T source, Function<T, String> getContext);
}
