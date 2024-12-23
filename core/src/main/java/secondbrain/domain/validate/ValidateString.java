package secondbrain.domain.validate;


import java.util.function.Function;

public interface ValidateString {

    String throwIfEmpty(String value);

    <T> T throwIfEmpty(T source, Function<T, String> getContext);
}
