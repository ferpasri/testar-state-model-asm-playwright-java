package org.testar.asm.playwright.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testar.evidence.EvidenceJson;
import static org.junit.jupiter.api.Assertions.*;

final class ExplorationBenchmarkIT {
    @Test
    void allModesExecuteTheSameActionsAndKeepValidEvidence(@TempDir Path directory) throws Exception {
        Path output = ExplorationBenchmark.run(new BenchmarkSettings(3, 1, 1, 2, 1, 2,
            true, List.of(), directory));
        JsonNode results = EvidenceJson.parse(Files.readString(output.resolve("results.json")));
        assertEquals(6, results.get("samples").size());
        JsonNode reference = results.get("samples").get(0).get("action_log");
        assertEquals("click", reference.get(0).get("type").asText());
        assertEquals("fill", reference.get(1).get("type").asText());
        assertEquals("click", reference.get(2).get("type").asText());
        int warmups = 0;
        Path historical = null;
        for (JsonNode sample : results.get("samples")) {
            assertEquals(reference, sample.get("action_log"));
            warmups += sample.get("warmup").asBoolean() ? 1 : 0;
            assertEquals(4, sample.get("calls").get("action_derivation").asInt());
            double sum = 0;
            for (var category : sample.get("seconds").properties()) {
                if (!category.getKey().equals("total")) {
                    sum += category.getValue().asDouble();
                }
            }
            assertEquals(sample.get("seconds").get("total").asDouble(), sum, 1e-9);
            if (sample.get("mode").asText().equals("playwright_baseline")) {
                assertTrue(sample.get("trace").isNull());
                assertEquals(0, sample.get("seconds").get("dom_capture").asDouble());
            } else {
                assertEquals(3, sample.get("trace").get("executions").asInt());
                assertEquals(3, sample.get("trace").get("transitions").asInt());
                assertEquals(4, sample.get("trace").get("observations").asInt());
                assertEquals(0, sample.get("trace").get("screenshots").asInt());
                historical = Path.of(sample.get("evidence_directory").asText());
            }
        }
        assertEquals(3, warmups);
        assertTrue(Files.isRegularFile(output.resolve("fixture.html")));
        // A second comparison verifies screenshots and loading existing evidence into the ASM.
        Path withImages = ExplorationBenchmark.run(new BenchmarkSettings(1, 1, 0, 0, 0, 2,
            false, List.of(historical), directory));
        JsonNode images = EvidenceJson.parse(Files.readString(withImages.resolve("results.json")));
        for (JsonNode sample : images.get("samples")) {
            if (!sample.get("mode").asText().equals("playwright_baseline")) {
                assertEquals(2, sample.get("trace").get("screenshots").asInt());
                assertEquals(2, sample.get("calls").get("dom_capture").asInt());
            }
        }
        assertTrue(Files.isRegularFile(withImages.resolve("samples/round-1-asm/model.json")));
    }
}
