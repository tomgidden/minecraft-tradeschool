package cx.gid.minecraft.common.func;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/// Wraps a pure function so each distinct argument is computed once.
///
/// Java has no `@Memoize`: an annotation is inert metadata, and the only
/// things that could act on one are a runtime proxy (which puts reflection on
/// every call), an annotation processor (which cannot modify the method it
/// annotates, only generate new classes beside it), or a build-time bytecode
/// rewrite. All three cost more than the `computeIfAbsent` they would be
/// hiding, so this is a plain wrapper instead.
///
/// Minecraft ships `Util#memoize`, which is the same idea. This exists for
/// the two-argument case, where `Util` allocates a `Pair` on *every* call to
/// key its cache; nesting a map per first argument allocates nothing on a
/// hit, at the cost of one map per distinct first argument.
///
/// ```java
/// private final BiFunction<String, String, String> lookup =
///     Memo.of(this::resolve);
/// ```
///
/// ## When not to use this
///
/// The cache is unbounded and never evicts, which imposes two rules.
///
/// The **key space must be bounded** by something other than time. Locales
/// and translation keys qualify; player names, positions and timestamps do
/// not, and memoising on those is a slow leak.
///
/// A memo over an instance method must be held in an **instance** field, not
/// a static one. `Memo.of(this::resolve)` captures `this`, so a static field
/// would pin that object -- and everything it reaches -- for the life of the
/// process.
///
/// The wrapped function must also be **pure**, and pure with respect to
/// anything that can change during a run. A function reading mutable state
/// will keep answering with whatever that state held the first time it was
/// asked.
public final class Memo {

  /// Not instantiable; this is a namespace for the factories below.
  private Memo() {}

  /// Memoises a single-argument function.
  ///
  /// `computeIfAbsent` is enough synchronisation on its own: the map only
  /// ever grows, and two threads racing the same miss agree on the answer,
  /// so the race costs a duplicated call and nothing else.
  ///
  /// @throws NullPointerException if the function returns null for some
  /// argument -- `ConcurrentHashMap` cannot store one, and a memo that
  /// silently declined to cache would be worse than the throw. Return a
  /// sentinel from the function instead.
  public static <T, R> Function<T, R> of(Function<T, R> fn) {
    Map<T, R> cache = new ConcurrentHashMap<>();
    return t -> cache.computeIfAbsent(t, fn);
  }

  /// Memoises a two-argument function, nesting a map per first argument.
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
  public static <T, U, R> BiFunction<T, U, R> of(BiFunction<T, U, R> fn) {
    Map<T, Map<U, R>> cache = new ConcurrentHashMap<>();
    return (t, u) -> cache.computeIfAbsent(t, k -> new ConcurrentHashMap<>())
                         .computeIfAbsent(u, k -> fn.apply(t, k));
  }

  /// Memoises a no-argument supplier, so it runs at most once.
  ///
  /// Unlike the map-backed forms this tolerates a null result, caching it as
  /// the answer rather than re-running the supplier forever.
  public static <T> Supplier<T> of(Supplier<T> fn) {
    // A one-element holder, rather than a volatile field pair, so the
    // "computed" flag and the value cannot be observed out of step.
    record Holder<T>(T value) {}

    // Written once under the lock and read without one afterwards; the
    // publication is safe because the record's field is final.
    var holder = new java.util.concurrent.atomic.AtomicReference<Holder<T>>();

    return () -> {
      Holder<T> current = holder.get();
      if (current == null) {
        // May run on more than one thread in a race. The supplier is pure,
        // so the losers' results are simply discarded.
        holder.compareAndSet(null, new Holder<>(fn.get()));
        current = holder.get();
      }
      return current.value();
    };
  }
}
