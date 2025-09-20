package secondbrain.domain.mutex;

public interface MutexCallback<T> {
    T apply();
}
