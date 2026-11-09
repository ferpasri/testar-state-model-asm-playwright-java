package org.testar.asm.playwright.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.testar.asm.AsmRun;
import org.testar.asm.EvidenceSources;
import org.testar.asm.playwright.ActionDerivation;
import org.testar.asm.playwright.ActionDerivationOptions;
import org.testar.asm.playwright.BrowserAction;
import org.testar.asm.playwright.ExplorationOptions;
import org.testar.asm.playwright.PlaywrightExploration;
import org.testar.asm.playwright.PlaywrightExplorationDriver;
import org.testar.asm.playwright.StateWithoutActions;
import org.testar.evidence.ActionExecutionStatus;
import org.testar.evidence.AvailableActionScope;
import org.testar.evidence.EvidenceJson;
import org.testar.evidence.EvidenceReader;
import org.testar.evidence.playwright.CaptureOptions;
import org.testar.evidence.playwright.EvidenceOptions;
import org.testar.evidence.playwright.PlaywrightEvidence;

/** Compare identical browser work with native Playwright, evidence recording, and ASM selection. */
public final class ExplorationBenchmark {
    static final List<String> MODES = List.of("playwright_baseline", "evidence", "asm");
    static final List<String> CATEGORIES = List.of("startup", "model_setup", "readiness", "action_derivation",
        "applicability", "browser_actions", "dom_capture", "screenshots", "recording_and_coordination",
        "model_update", "selection", "cleanup", "other", "total");

    private ExplorationBenchmark() {
    }

    public static void main(String[] arguments) throws Exception {
        BenchmarkSettings settings = BenchmarkSettings.parse(arguments);
        if (settings != null) {
            run(settings);
        }
    }

    /** Rotate mode order each round and save completed samples before proceeding to the next one. */
    static Path run(BenchmarkSettings settings) throws Exception {
        String name = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS"))
            + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path output = settings.output().resolve(name).toAbsolutePath().normalize();
        Files.createDirectories(settings.output());
        Files.createDirectory(output);
        List<Map<String, Object>> samples = new ArrayList<>();
        String browserVersion;
        try (var fixture = new BenchmarkFixture(output, settings.widgets(), settings.properties(), settings.states());
             Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                 .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             Browser browser = playwright.chromium().launch(launchOptions())) {
            browserVersion = browser.version();
            for (int round = 0; round < settings.warmups() + settings.repeats(); round++) {
                Object reference = null;
                for (int offset = 0; offset < MODES.size(); offset++) {
                    String mode = MODES.get((round + offset) % MODES.size());
                    Path sampleOutput = output.resolve("samples").resolve("round-" + (round + 1) + "-" + mode);
                    var sample = sample(browser, fixture.url(), mode, settings, sampleOutput);
                    Object actions = sample.get("action_log");
                    if (reference != null && !reference.equals(actions)) {
                        throw new IllegalStateException("Modes followed different actions; comparison rejected");
                    }
                    reference = actions;
                    boolean warmup = round < settings.warmups();
                    sample.put("round", round + 1);
                    sample.put("warmup", warmup);
                    samples.add(sample);
                    @SuppressWarnings("unchecked")
                    Map<String, Double> seconds = (Map<String, Double>) sample.get("seconds");
                    System.out.printf(Locale.ROOT, "%s round %d, %s: %.3fs%n",
                        warmup ? "Warm-up" : "Measured", round + 1, mode, seconds.get("total"));
                    save(output.resolve("samples.json"), samples);
                }
            }
        }
        var medians = medians(samples);
        printSummary(medians);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("settings", Map.of("actions", settings.actions(), "repeats", settings.repeats(), "warmups", settings.warmups(),
            "widgets", settings.widgets(), "properties", settings.properties(), "states", settings.states(),
            "no_screenshots", settings.noScreenshots(), "existing_traces", settings.existingTraces().stream().map(Path::toString).toList()));
        result.put("environment", Map.of("java", System.getProperty("java.version"), "os", System.getProperty("os.name"),
            "architecture", System.getProperty("os.arch"), "browser", browserVersion,
            "libraries", Map.of("playwright", dependencyVersion("com.microsoft.playwright", "playwright"),
                "asm", dependencyVersion("org.testar", "testar-state-model-asm"),
                "evidence", dependencyVersion("org.testar", "testar-evidence"),
                "evidence_adapter", dependencyVersion("org.testar", "testar-evidence-playwright-java-adapter"))));
        result.put("samples", samples);
        result.put("medians", medians);
        save(output.resolve("results.json"), result);
        System.out.println("Results and traces: " + output);
        return output;
    }

    /** Each sample has isolated cookies/storage. Its trace is validated after the elapsed-time measurement. */
    static Map<String, Object> sample(Browser browser, String url, String mode,
                                      BenchmarkSettings settings, Path output) throws Exception {
        Files.createDirectories(output);
        BenchmarkTimings timings = new BenchmarkTimings();
        BenchmarkBrowser measured = new BenchmarkBrowser(timings);
        long started = System.nanoTime();
        BrowserContext context = timings.call("startup", browser::newContext);
        PlaywrightEvidence recorder = null;
        JsonNode model = null;
        try {
            Page page;
            try (var ignored = timings.measure("startup")) {
                page = measured.page(context.newPage());
                page.navigate(url);
                if (!mode.equals("playwright_baseline")) {
                    CaptureOptions defaults = CaptureOptions.defaults();
                    CaptureOptions capture = new CaptureOptions(Math.min(5000, settings.widgets() * 2 + 64),
                        defaults.maxValueCharacters(), defaults.maxTotalCharacters(), defaults.maxAttributesPerWidget(),
                        defaults.computedStyles(), defaults.excludedAttributes(), !settings.noScreenshots());
                    recorder = PlaywrightEvidence.startTrace(page,
                        new EvidenceOptions(output.resolve("traces"), "benchmark", null, mode, capture));
                }
            }
            var derivation = new ActionDerivationOptions(Map.of("#entry", "benchmark text"), List.of());
            if (mode.equals("playwright_baseline")) {
                // Playwright has no native ASM. This benchmark script takes the only eligible action.
                ready(page, timings);
                List<BrowserAction> available = derive(page, derivation, timings);
                for (int action = 0; action < settings.actions(); action++) {
                    BrowserAction selected = one(available);
                    check(selected.applicable(), "The fixture action unexpectedly became inapplicable");
                    selected.execute(5000);
                    ready(page, timings);
                    available = derive(page, derivation, timings);
                }
            } else {
                var options = ExplorationOptions.builder().configuration(PlaywrightExploration.defaultConfiguration(settings.actions()))
                    .actionDerivationOptions(derivation).stateWithoutActions(StateWithoutActions.STOP)
                    .deriveActions((current, rules) -> timings.call("action_derivation",
                        () -> ActionDerivation.deriveActions(current, rules)))
                    .waitUntilReady(current -> {
                        try (var ignored = timings.measure("readiness")) {
                            current.waitForLoadState(LoadState.DOMCONTENTLOADED);
                        }
                    }).build();
                PlaywrightExplorationDriver driver;
                try (var ignored = timings.measure("recording_and_coordination")) {
                    driver = new PlaywrightExplorationDriver(page, recorder, options);
                }
                AsmRun asm = null;
                ArrayNode historicalRuns = JsonNodeFactory.instance.arrayNode();
                if (mode.equals("asm")) {
                    try (var ignored = timings.measure("model_setup")) {
                        ArrayNode sources = JsonNodeFactory.instance.arrayNode();
                        for (int index = 0; index < settings.existingTraces().size(); index++) {
                            JsonNode source = EvidenceSources.read(settings.existingTraces().get(index), "historical:" + index);
                            sources.add(source);
                            for (JsonNode run : source.get("evidence").get("runs")) {
                                historicalRuns.add(JsonNodeFactory.instance.objectNode()
                                    .put("source", source.get("id").asText()).put("run", run.get("id").asText()));
                            }
                        }
                        asm = new AsmRun(PlaywrightExploration.defaultConfiguration(settings.actions()),
                            sources, historicalRuns, size -> 0);
                    }
                }
                for (int action = 0; action < settings.actions(); action++) {
                    var observation = timings.call("recording_and_coordination", driver::observe);
                    JsonNode selection;
                    one(asList(observation.context().get("choices")));
                    if (asm == null) {
                        selection = JsonNodeFactory.instance.objectNode()
                            .put("choice_id", observation.context().get("choices").get(0).get("choice_id").asText());
                    } else {
                        // Call the real ASM lifecycle directly so update and selection costs remain separate.
                        try (var ignored = timings.measure("model_update")) {
                            asm.updateEvidence("observe-" + action, observation.sources(), combine(historicalRuns, observation.selectedRuns()));
                        }
                        try (var ignored = timings.measure("recording_and_coordination")) {
                            asm.setContext(observation.context());
                        }
                        try (var ignored = timings.measure("selection")) {
                            selection = asm.select();
                        }
                        check(selection.has("choice_id"), "ASM stopped before the requested action count");
                    }
                    JsonNode execution = timings.call("recording_and_coordination", () -> driver.prepareExecution(selection));
                    check(execution != null, "The fixture unexpectedly required a fresh observation");
                    if (asm != null) {
                        try (var ignored = timings.measure("recording_and_coordination")) {
                            asm.beginExecution(selection, execution);
                        }
                    }
                    var result = timings.call("recording_and_coordination", () -> driver.execute(selection, execution));
                    if (asm != null) {
                        try (var ignored = timings.measure("model_update")) {
                            asm.reportExecution(execution, "execute-" + action, result.sources(), combine(historicalRuns, result.selectedRuns()));
                        }
                    }
                }
                if (asm != null) {
                    try (var ignored = timings.measure("recording_and_coordination")) {
                        JsonNode stopped = asm.explore(driver);
                        check(stopped.path("reason").asText().equals("action_limit_reached"),
                            "Unexpected ASM stop response: " + stopped);
                        model = asm.model();
                    }
                }
            }
            // This browser counter read is included in total as unclassified overhead.
            check(((Number) page.evaluate("() => window.benchmarkStep")).intValue() == settings.actions(),
                "Browser effects differ from the requested action count");
            check(measured.actions.size() == settings.actions(), "Browser action log has an unexpected count");
        } finally {
            try (var ignored = timings.measure("cleanup")) {
                try {
                    if (recorder != null) {
                        recorder.close();
                    }
                } finally {
                    context.close();
                }
            }
        }
        double elapsed = (System.nanoTime() - started) / 1_000_000_000.0;
        Map<String, Double> seconds = new LinkedHashMap<>();
        for (String category : CATEGORIES) {
            if (!category.equals("other") && !category.equals("total")) {
                seconds.put(category, timings.seconds.getOrDefault(category, 0.0));
            }
        }
        seconds.put("other", Math.max(0, elapsed - seconds.values().stream().mapToDouble(Double::doubleValue).sum()));
        seconds.put("total", elapsed);
        if (model != null) {
            Files.writeString(output.resolve("model.json"), model.toString());
        }
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("mode", mode);
        sample.put("seconds", seconds);
        sample.put("calls", timings.calls);
        sample.put("action_log", measured.actions);
        sample.put("evidence_directory", recorder == null ? null : recorder.directory().toString());
        sample.put("trace", recorder == null ? null : validateTrace(recorder.directory(), settings.actions(), !settings.noScreenshots()));
        return sample;
    }

    private static Map<String, Object> validateTrace(Path directory, int actions, boolean screenshots) throws Exception {
        var trace = EvidenceReader.read(directory);
        check(trace.actionExecutions().size() == actions && trace.recordedTransitions().size() == actions
            && trace.observations().size() == (long) actions + 1, "Incorrect trace counts");
        check(trace.actionExecutions().stream().allMatch(record -> record.status() == ActionExecutionStatus.COMPLETED),
            "A browser execution did not complete");
        check(trace.testRuns().stream().allMatch(record -> record.finished())
            && trace.testSequences().stream().allMatch(record -> record.finished()), "Trace lifecycle did not finish");
        for (var observation : trace.observations()) {
            check(observation.availableActionScope() == AvailableActionScope.ELIGIBLE && observation.availableActions().size() == 1,
                "An observation lost its next available action");
            check(observation.artifacts().size() == (screenshots ? 1 : 0), "Screenshot counts differ");
        }
        return Map.of("observations", trace.observations().size(), "executions", trace.actionExecutions().size(),
            "transitions", trace.recordedTransitions().size(), "screenshots",
            trace.observations().stream().mapToInt(observation -> observation.artifacts().size()).sum(),
            "jsonl_bytes", Files.size(directory.resolve("evidence.jsonl")));
    }

    private static void ready(Page page, BenchmarkTimings timings) {
        try (var ignored = timings.measure("readiness")) {
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        }
    }

    private static List<BrowserAction> derive(Page page, ActionDerivationOptions options, BenchmarkTimings timings) throws Exception {
        return timings.call("action_derivation", () -> ActionDerivation.deriveActions(page, options));
    }

    private static <T> T one(List<T> choices) {
        check(choices.size() == 1, "The fixture must supply exactly one eligible action");
        return choices.get(0);
    }

    private static List<JsonNode> asList(JsonNode values) {
        List<JsonNode> result = new ArrayList<>();
        values.forEach(result::add);
        return result;
    }

    private static JsonNode combine(ArrayNode historical, JsonNode live) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode().addAll(historical);
        live.forEach(result::add);
        return result;
    }

    private static BrowserType.LaunchOptions launchOptions() {
        var options = new BrowserType.LaunchOptions().setHeadless(true);
        String channel = System.getProperty("playwright.channel");
        if (channel != null && !channel.isBlank()) {
            options.setChannel(channel);
        }
        return options;
    }

    static Map<String, Map<String, Double>> medians(List<Map<String, Object>> samples) {
        Map<String, Map<String, Double>> result = new LinkedHashMap<>();
        for (String category : CATEGORIES) {
            Map<String, Double> modes = new LinkedHashMap<>();
            for (String mode : MODES) {
                List<Double> values = new ArrayList<>();
                for (Map<String, Object> sample : samples) {
                    if (mode.equals(sample.get("mode")) && Boolean.FALSE.equals(sample.get("warmup"))) {
                        @SuppressWarnings("unchecked")
                        Map<String, Double> seconds = (Map<String, Double>) sample.get("seconds");
                        values.add(seconds.get(category));
                    }
                }
                check(!values.isEmpty(), "Each mode requires a measured sample");
                values.sort(Double::compareTo);
                int middle = values.size() / 2;
                modes.put(mode, values.size() % 2 == 0 ? (values.get(middle - 1) + values.get(middle)) / 2 : values.get(middle));
            }
            result.put(category, modes);
        }
        return result;
    }

    private static void printSummary(Map<String, Map<String, Double>> medians) {
        System.out.printf("%n%-30s %20s %12s %12s%n", "Median seconds", "Playwright baseline", "Evidence", "ASM");
        medians.forEach((category, modes) -> System.out.printf(Locale.ROOT, "%-30s %20.3f %12.3f %12.3f%n",
            category, modes.get("playwright_baseline"), modes.get("evidence"), modes.get("asm")));
        var totals = medians.get("total");
        System.out.printf(Locale.ROOT, "%nEvidence/baseline: %.2fx%nASM/baseline: %.2fx%n",
            totals.get("evidence") / totals.get("playwright_baseline"), totals.get("asm") / totals.get("playwright_baseline"));
    }

    private static String dependencyVersion(String group, String artifact) throws Exception {
        String path = "/META-INF/maven/" + group + "/" + artifact + "/pom.properties";
        try (var input = ExplorationBenchmark.class.getResourceAsStream(path)) {
            if (input == null) {
                return "unavailable";
            }
            var properties = new java.util.Properties();
            properties.load(input);
            return properties.getProperty("version", "unavailable");
        }
    }

    private static void save(Path path, Object value) throws Exception {
        Files.writeString(path, EvidenceJson.write(EvidenceJson.tree(value)));
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
