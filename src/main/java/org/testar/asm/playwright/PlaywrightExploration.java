package org.testar.asm.playwright;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.Page;
import java.io.IOException;
import java.util.Objects;
import org.testar.asm.AsmRun;
import org.testar.asm.EvidenceSources;
import org.testar.evidence.EvidenceJson;
import org.testar.evidence.playwright.EvidencePlaywright;
import org.testar.evidence.playwright.PlaywrightEvidence;
import static org.testar.asm.playwright.JsonData.*;

/** Hand a prepared Playwright page to the ASM while retaining caller ownership of its browser. */
public final class PlaywrightExploration implements AutoCloseable {
    private final AsmRun asm;
    private final PlaywrightEvidence recorder;
    private final PlaywrightExplorationDriver driver;
    private final boolean ownsRecorder;
    private boolean closed;

    public PlaywrightExploration(Page page) throws IOException {
        this(page, ExplorationOptions.defaults());
    }

    /** Validate configuration and historical evidence before creating a new trace. */
    public PlaywrightExploration(Page page, ExplorationOptions options) throws IOException {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(options, "options");
        var sources = array();
        var runs = array();
        for (int index = 0; index < options.existingTraces.size(); index++) {
            JsonNode source = EvidenceSources.read(options.existingTraces.get(index), "historical:" + index);
            sources.add(source);
            for (JsonNode run : source.get("evidence").get("runs")) {
                runs.add(object("source", source.get("id"), "run", run.get("id")));
            }
        }
        asm = new AsmRun(options.configuration, sources, runs, options.chooseIndex);
        PlaywrightEvidence selected = options.recorder;
        boolean owned = false;
        if (selected == null) {
            try {
                selected = EvidencePlaywright.evidenceFor(page);
            } catch (IllegalArgumentException nativePage) {
                selected = PlaywrightEvidence.startTrace(page, options.evidenceOptions);
                owned = true;
            }
        }
        if (!selected.isFor(page)) {
            throw new IllegalArgumentException("The recorder must belong to the supplied page");
        }
        recorder = selected;
        ownsRecorder = owned;
        driver = new PlaywrightExplorationDriver(page, recorder, options);
    }

    /** Build the same default selection and matching configuration used by the Python integration. */
    public static JsonNode defaultConfiguration(int actionLimit) {
        if (actionLimit <= 0) {
            throw new IllegalArgumentException("actionLimit must be positive");
        }
        ObjectNode result = (ObjectNode) EvidenceJson.parse("""
            {"abstraction":{"version":1,"name":"Page address, title, and target identity",
              "state":[{"name":"address","keys":["ui.url"],"required":true},
                       {"name":"title","keys":["ui.title"],"required":true}],
              "capture":"same","actions":{"target":[{"name":"target","keys":[
                "playwright.attribute.id","playwright.attribute.name","playwright.dom.path"],"required":true}]}},
             "asms":[{"name":"unvisited","action_limit":1,"unmatched_choices":"exclude"},
                     {"name":"random","action_limit":1}],
             "total_action_limit":1}
            """);
        result.put("total_action_limit", actionLimit);
        for (JsonNode entry : result.get("asms")) {
            ((ObjectNode) entry).put("action_limit", actionLimit);
        }
        return result;
    }

    public AsmRun asm() {
        return asm;
    }

    public PlaywrightEvidence recorder() {
        return recorder;
    }

    public PlaywrightExplorationDriver driver() {
        return driver;
    }

    public ExplorationResult run() throws Exception {
        return run(1000);
    }

    /** Start or continue the ASM run; discard a cached result if script steps ran between calls. */
    public ExplorationResult run(int maxCycles) throws Exception {
        if (closed) {
            throw new IllegalStateException("Exploration has closed");
        }
        driver.refreshOnNextObservation();
        JsonNode stopped = asm.explore(driver, maxCycles);
        return new ExplorationResult(stopped, asm.model(), recorder.directory());
    }

    public static ExplorationResult explore(Page page) throws Exception {
        return explore(page, ExplorationOptions.defaults());
    }

    /** Convenience call: finish only an owned recorder, leaving the supplied page open. */
    public static ExplorationResult explore(Page page, ExplorationOptions options) throws Exception {
        try (PlaywrightExploration exploration = new PlaywrightExploration(page, options)) {
            return exploration.run();
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            try {
                if (ownsRecorder) {
                    recorder.close();
                }
            } finally {
                closed = true;
            }
        }
    }
}
