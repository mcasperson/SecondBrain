package secondbrain.domain.exceptionhandling;

public interface ExceptionHandler {
    String getExceptionMessage(Throwable e);
}
