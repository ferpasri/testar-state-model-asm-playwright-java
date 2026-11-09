# Local browser benchmark

Compare the same browser actions in three modes:

| Mode | Action selection | Evidence | ASM |
| --- | --- | --- | --- |
| `playwright_baseline` | A benchmark script executes the fixture's only eligible action. | No | No |
| `evidence` | The same choice is executed through the Playwright exploration driver. | Yes | No |
| `asm` | The Java ASM selects each action using the real driver and recorded evidence. | Yes | Yes |

Playwright supplies browser operations, not a native ASM. The baseline is a script with a requested action count. All modes derive actions using the integration's default helper.

The fixture uses the same HTML and generated widget data as the Python benchmark. It alternates click/fill actions, with exactly one eligible action per step. This measures execution overhead rather than exploration quality on a branching application.

## Run

From the repository root, using Java, Maven, and an installed Playwright Chromium browser:

~~~powershell
mvn test-compile exec:java "-Dexec.classpathScope=test" "-Dexec.mainClass=org.testar.asm.playwright.benchmark.ExplorationBenchmark" "-Dexec.args=--actions 20 --repeats 3 --warmups 1"
mvn test-compile exec:java "-Dexec.classpathScope=test" "-Dexec.mainClass=org.testar.asm.playwright.benchmark.ExplorationBenchmark" "-Dexec.args=--actions 20 --repeats 3 --warmups 1 --no-screenshots"
~~~

Increase the captured page size:

~~~powershell
mvn test-compile exec:java "-Dexec.classpathScope=test" "-Dexec.mainClass=org.testar.asm.playwright.benchmark.ExplorationBenchmark" "-Dexec.args=--actions 50 --repeats 3 --warmups 1 --widgets 300 --properties 12 --states 10"
~~~

Use `--help` for the option list. An installed Chrome can be selected with `-Dplaywright.channel=chrome`.

| Option | Default and meaning |
| --- | --- |
| `--actions N` | 20 browser operations per sample. |
| `--repeats N` | 3 measured rounds, each containing all three modes. |
| `--warmups N` | 1 initial round excluded from medians. |
| `--widgets N` | 50 additional non-actionable div/span pairs; range 0..2000. |
| `--properties N` | 4 additional data attributes on each generated div; range 0..32. |
| `--states N` | 5 page titles visited cyclically. |
| `--no-screenshots` | Disable screenshots in both recording modes; enabled otherwise. |
| `--existing-trace PATH` | Optional individual trace directory for ASM mode; repeat for several traces. |
| `--output PATH` | `target/benchmarks`, the parent for unique results folders. |

Each sample uses a fresh browser context. Mode order rotates each round. Browser launch, fixture generation, and trace validation are outside sample timing. Warm-ups help browser and JVM initialization settle; compare repeated measurements on the same laptop and browser build.

## Timings

Nested operations are subtracted from their parent category. Each sample also records the total elapsed time, including cleanup. Medians are calculated separately for every category and mode, so median rows need not add up exactly.

| Category | Meaning |
| --- | --- |
| `startup` | Context/page creation, fixture navigation, and recorder initialization. |
| `model_setup` | Read existing evidence and construct the initial ASM. |
| `readiness` | Wait for DOMContentLoaded. |
| `action_derivation` | Derive and describe the currently eligible action. |
| `applicability` | Native locator usability/identity checks, including locator-count checks during recording. |
| `browser_actions` | Native click/fill operations and Playwright waits. |
| `dom_capture` | Browser DOM extraction and transport. |
| `screenshots` | Capture PNG screenshots. |
| `recording_and_coordination` | Driver work, DOM-to-record mapping, action binding, validation, persistence, source preparation, and lifecycle coordination. Nested browser/capture operations are excluded. |
| `model_update` | Validate/accept new evidence and update the abstract model. |
| `selection` | Ask the real ASM to select the next action. |
| `cleanup` | Finish the recorder and close the context. |
| `other` | Remaining setup and browser-counter checks inside total time. |
| `total` | Entire sample, including cleanup and excluding subsequent saved-trace validation. |

To measure model updates and selection separately without production instrumentation, the benchmark drives the public ASM lifecycle: update evidence, set context, select, begin execution, execute through the driver, and report its result. It verifies the ASM's overall action limit at completion.

Java's recording/coordination category is combined. Python separates those categories and includes record mapping in DOM capture. Compare totals and screenshot effects directly for equivalent settings; treat detailed categories as diagnostic rather than exact cross-language equivalents. The two runners also use their language-specific Playwright bindings and capture implementations. Existing traces add model-processing work; traces recorded at another fixture address may not match current states under default URL-based abstraction.

## Results and verification

A unique results folder contains the generated `fixture.html`, incremental `samples.json`, and final `results.json` with settings, environment/library versions, medians, action logs, and trace summaries. Each sample has its own directory; recording modes retain evidence under `traces/`, and ASM mode saves `model.json`.

The benchmark rejects differing action logs or browser counters. After timing, the evidence reader checks references, lifecycle, artifact hashes, completed executions, transitions, observation counts, screenshot counts, and the next available action. A recording sample has one initial observation plus one result per action.

Timing wrappers live only in test-scope benchmark code. The benchmark uses local HTTP and does not contact ParaBank.
