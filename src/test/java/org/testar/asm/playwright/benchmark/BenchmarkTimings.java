package org.testar.asm.playwright.benchmark;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Subtract nested work so each measured interval belongs to one category. */
final class BenchmarkTimings {
    private final LongSupplier clock;
    private final ArrayDeque<Scope> stack = new ArrayDeque<>();
    final Map<String, Double> seconds = new LinkedHashMap<>();
    final Map<String, Integer> calls = new LinkedHashMap<>();

    BenchmarkTimings() {
        this(System::nanoTime);
    }

    BenchmarkTimings(LongSupplier clock) {
        this.clock = clock;
    }

    @FunctionalInterface
    interface Operation<T> {
        T run() throws Exception;
    }

    <T> T call(String category, Operation<T> operation) throws Exception {
        try (var ignored = measure(category)) {
            return operation.run();
        }
    }

    Scope measure(String category) {
        Scope scope = new Scope(category, clock.getAsLong());
        stack.push(scope);
        return scope;
    }

    final class Scope implements AutoCloseable {
        private final String category;
        private final long started;
        private long children;
        private boolean closed;

        private Scope(String category, long started) {
            this.category = category;
            this.started = started;
        }

        @Override
        public void close() {
            if (!closed) {
                long elapsed = clock.getAsLong() - started;
                if (stack.pop() != this) {
                    throw new IllegalStateException("Timing scopes must close in their original nesting order");
                }
                seconds.merge(category, (elapsed - children) / 1_000_000_000.0, Double::sum);
                calls.merge(category, 1, Integer::sum);
                if (!stack.isEmpty()) {
                    stack.peek().children += elapsed;
                }
                closed = true;
            }
        }
    }
}
