package org.testar.asm.playwright.benchmark;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Workload sizes and optional inputs; defaults match the Python browser benchmark. */
record BenchmarkSettings(int actions, int repeats, int warmups, int widgets, int properties, int states,
                         boolean noScreenshots, List<Path> existingTraces, Path output) {
    BenchmarkSettings {
        if (actions < 1 || repeats < 1 || warmups < 0 || states < 1 || (long) repeats + warmups > Integer.MAX_VALUE
                || widgets < 0 || widgets > 2000 || properties < 0 || properties > 32) {
            throw new IllegalArgumentException("Use positive actions/repeats/states, nonnegative warmups, widgets 0..2000, properties 0..32");
        }
        existingTraces = List.copyOf(existingTraces);
        java.util.Objects.requireNonNull(output, "output");
    }

    static BenchmarkSettings parse(String[] arguments) {
        Map<String, String> values = new HashMap<>();
        List<Path> traces = new ArrayList<>();
        boolean noScreenshots = false;
        for (int index = 0; index < arguments.length; index++) {
            String option = arguments[index];
            if (option.equals("--help")) {
                System.out.println("--actions N --repeats N --warmups N --widgets N --properties N --states N"
                    + " --no-screenshots --existing-trace PATH --output PATH");
                return null;
            }
            if (option.equals("--no-screenshots")) {
                noScreenshots = true;
                continue;
            }
            if (!List.of("--actions", "--repeats", "--warmups", "--widgets", "--properties", "--states",
                    "--existing-trace", "--output").contains(option)) {
                throw new IllegalArgumentException("Unknown option: " + option);
            }
            if (++index == arguments.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            if (option.equals("--existing-trace")) {
                traces.add(Path.of(arguments[index]));
            } else {
                values.put(option.substring(2), arguments[index]);
            }
        }
        return new BenchmarkSettings(number(values, "actions", 20), number(values, "repeats", 3),
            number(values, "warmups", 1), number(values, "widgets", 50), number(values, "properties", 4),
            number(values, "states", 5), noScreenshots, traces, Path.of(values.getOrDefault("output", "target/benchmarks")));
    }

    private static int number(Map<String, String> values, String name, int fallback) {
        return Integer.parseInt(values.getOrDefault(name, Integer.toString(fallback)));
    }
}
