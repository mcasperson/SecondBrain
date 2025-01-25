package secondbrain.domain.validate;


import java.util.List;

public interface ValidateList {
    <T> List<T> throwIfEmpty(List<T> value);

    <T> List<T> throwIfInsufficient(List<T> value, int size);
}
