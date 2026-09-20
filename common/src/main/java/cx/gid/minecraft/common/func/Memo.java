package cx.gid.minecraft.common.func;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/// Wraps a pure function so each distinct argument is computed once.
///
/// The cache isn't bounded and never evicts, so:
///
/// - The key space must be carefully chosen to put a bound on the cache,
///   ie. avoiding timestamp, player names, positions, etc., which would
///   make the cache effectively unbounded and leak memory.
///
/// - Instance method memoization must be held in an instance field, not
///   a static one, so the cache is pinned for the life of the object,
///   not the life of the server process.
///
/// - The function being memoized must be pure, and pure with respect to
///   anything that can change during a run. A function reading mutable state.
///   Otherwise, the cache will return stale results.
public final class Memo
{
  /// Not instantiable; this is a namespace for the factories below.
  private Memo() {}

  /// Memoises a single-argument function.
  ///
  /// @throws NullPointerException if the function returns null for an argument.
  public static <T, R> Function<T, R> of(Function<T, R> fn)
  {
    // The cache for this function, keyed by function argument and valued
    // by result. It's bound to the memo function so it can be garbage
    // collected along with the memo function.
    Map<T, R> cache = new ConcurrentHashMap<>();

    // `computeIfAbsent` is enough synchronisation on its own, assuming
    // the function is pure and cheap; a race will just result in the
    // function being repeated for the same argument and the same result.
    return t -> cache.computeIfAbsent(t, fn);
  }

  /// Memoises a two-argument function.
  ///
  /// The nesting is what avoids allocating a composite key per call, and it
  /// also groups the results: everything derived from one first argument
  /// sits in one map. That suits arguments where the first is drawn from a
  /// small set (a locale, a dimension) and the second from a larger one.
  ///
  /// Note the two `computeIfAbsent` calls act on *different* maps -- the
  /// outer one is complete before the inner begins. Recursing into the same
  /// map from inside `computeIfAbsent` can deadlock a `ConcurrentHashMap`,
  /// so a wrapped function must never call back into its own memo.
  public static <T, U, R> BiFunction<T, U, R> of(BiFunction<T, U, R> fn)
  {
    // There would be two obvious ways to do this:
    // a) The simple extension of the single function `of`, by mapping
    //    the (t,u) tuple as a composite key for the result, ie. (a,b)->result.
    //
    // b) The mapping of the first arg to a second map that maps the
    //    second arg to the result.  ie. a -> b -> result.
    //
    // (a) is simpler but involves creating the tuple each time, whereas
    // (b) is more complicated, but more efficient _and_ groups the results
    // nicely.

    // Following (b), we use a map of maps, keyed by the first argument.
    Map<T, Map<U, R>> cache = new ConcurrentHashMap<>();

    // Wrap this up so it's a simple function of two arguments, that
    // then does the two-step lookup.
    return (t, u) -> cache.computeIfAbsent(t, k -> new ConcurrentHashMap<>()).computeIfAbsent(u, k -> fn.apply(t, k));
  }

  /// Memoises a no-argument supplier, so it runs at most once.
  ///
  /// Unlike the map-backed forms this tolerates a null result, caching it as
  /// the answer rather than re-running the supplier forever.
  public static <T> Supplier<T> of(Supplier<T> fn)
  {
    // A one-element holder, rather than a volatile field pair, so the
    // "computed" flag and the value cannot be observed out of step.
    record Holder<T>(T value) {
    }

    // Written once under the lock and read without one afterwards; the
    // publication is safe because the record's field is final.
    var holder = new java.util.concurrent.atomic.AtomicReference<Holder<T>>();

    return () ->
    {
      Holder<T> current = holder.get();

      if(current == null) {
        // May run on more than one thread in a race. The supplier is pure,
        // so the losers' results are simply discarded.
        holder.compareAndSet(null, new Holder<>(fn.get()));

        // Read the holder again, now that it's been published.
        current = holder.get();
      }

      // Return the value.
      return current.value();
    };
  }
}
