package secondbrain.domain.exceptionhandling;

import io.vavr.control.Try;

public interface ExceptionMapping {
    <T> Try<T> map(Try<T> tryObject);
}
