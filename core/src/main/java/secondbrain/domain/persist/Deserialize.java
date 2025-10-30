package secondbrain.domain.persist;

public interface Deserialize<T> {
    T deserialize(String json);
}
