package secondbrain.domain.tryext;

import io.vavr.CheckedFunction0;
import io.vavr.CheckedFunction1;
import io.vavr.control.Try;

public final class TryExtensions {
    /**
     * Provide a simple way to nest Try.withResources calls for two resources where the
     * second resource depends on the first.
     */
    public static <T1 extends AutoCloseable, T2 extends AutoCloseable, T3> Try<T3> withResources(
            final CheckedFunction0<T1> t1Supplier,
            final CheckedFunction1<T1, T2> t2Supplier,
            final CheckedFunction1<T2, T3> callback) {
        return Try.withResources(t1Supplier)
                .of(x -> Try.withResources(() -> t2Supplier.apply(x))
                        .of(callback))
                .get();
    }
}
