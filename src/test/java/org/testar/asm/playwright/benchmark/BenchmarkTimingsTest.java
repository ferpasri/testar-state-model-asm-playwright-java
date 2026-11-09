package org.testar.asm.playwright.benchmark;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class BenchmarkTimingsTest {
    @Test
    void screenshotTimeIsNotCountedAgainInsideRecordingTime() {
        long[] readings = {0, 1_000_000_000L, 3_000_000_000L, 7_000_000_000L};
        AtomicInteger next = new AtomicInteger();
        var timings = new BenchmarkTimings(() -> readings[next.getAndIncrement()]);
        try (var recording = timings.measure("recording_and_coordination")) {
            try (var screenshot = timings.measure("screenshots")) {
                // The two-second screenshot is nested within seven seconds of recording.
            }
        }
        assertEquals(2.0, timings.seconds.get("screenshots"));
        assertEquals(5.0, timings.seconds.get("recording_and_coordination"));
    }

    @Test
    void mediansExcludeWarmupsAndRetainTheMeasuredMode() {
        List<Map<String, Object>> samples = new ArrayList<>();
        for (String mode : ExplorationBenchmark.MODES) {
            for (double value : List.of(100.0, 1.0, 3.0)) {
                Map<String, Double> seconds = new LinkedHashMap<>();
                ExplorationBenchmark.CATEGORIES.forEach(category -> seconds.put(category, value));
                samples.add(Map.of("mode", mode, "warmup", value == 100.0, "seconds", seconds));
            }
        }
        var medians = ExplorationBenchmark.medians(samples);
        for (String mode : ExplorationBenchmark.MODES) {
            assertEquals(2.0, medians.get("total").get(mode));
        }
        assertThrows(IllegalArgumentException.class, () ->
            BenchmarkSettings.parse(new String[]{"--actions", "0"}));
        assertThrows(IllegalArgumentException.class, () ->
            BenchmarkSettings.parse(new String[]{"--unknown", "1"}));
    }
}
