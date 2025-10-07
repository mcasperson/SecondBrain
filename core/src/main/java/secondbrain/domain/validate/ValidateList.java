package secondbrain.domain.validate;


import java.util.List;
import java.util.function.Function;

public interface ValidateList {
    <T> List<T> throwIfEmpty(List<T> value);

    <T> List<T> throwIfEmpty(List<T> value, Function<T, String> getContext);

    <T> List<T> throwIfInsufficient(List<T> value, int size);
}
