# TESTAR Playwright ASM Java

Add model-based exploration to an existing Playwright Java page. The integration derives actions, asks the ASM to select one, executes it through Playwright, and records concrete evidence.

~~~java
import org.testar.asm.playwright.ActionDerivationOptions;
import org.testar.asm.playwright.ExplorationOptions;
import org.testar.asm.playwright.PlaywrightExploration;

var options = ExplorationOptions.builder()
    .configuration(PlaywrightExploration.defaultConfiguration(20))
    .actionDerivationOptions(new ActionDerivationOptions(
        java.util.Map.of("input[name='search']", "example"),
        java.util.List.of("a[href*='logout']")))
    .build();

// Keep existing scripted preparation, such as login, before this call.
var result = PlaywrightExploration.explore(page, options);
System.out.println(result.stopped().get("reason").asText());
System.out.println(result.evidenceDirectory());
~~~

`PlaywrightExploration.explore(page)` uses default settings: Unvisited ASM with Random fallback, an overall action limit, and state matching by page address/title. Action derivation offers visible enabled controls and same-origin links. Fill actions require explicitly configured values.

~~~text
Playwright script -> Playwright ASM integration -> Java ASM -> Java abstraction
                                 |
                         Java evidence adapter -> Java evidence -> concrete traces
                                 |
                        Official Playwright -> application
~~~

Native pages start a timestamped evidence trace when exploration begins. Pages created through `EvidencePlaywright` reuse their recorder, so scripted preparation and exploration share a trace. A supplied recorder remains under its caller's ownership. Exploration leaves the page and browser open.

## Build and test

Use Java 17 and Maven. The Maven dependencies declared in [pom.xml](pom.xml) must be available. For local development, run `mvn install` in the Java evidence library, abstraction library, ASM library, and evidence adapter repositories before building this project.

~~~powershell
mvn verify
mvn exec:java "-Dexec.mainClass=com.microsoft.playwright.CLI" "-Dexec.args=install chromium"
mvn verify -Pbrowser-tests
~~~

Browser tests use a local application. To use installed Google Chrome instead, run `mvn verify -Pbrowser-tests "-Dplaywright.channel=chrome"`.

The library coordinates are `org.testar:testar-state-model-asm-playwright`. Package and dependency versions live in [pom.xml](pom.xml).

## Examples and extension points

The [examples guide](docs/examples.md) provides three standalone ParaBank scripts: basic exploration, input values and filters, and custom abstraction with optional existing traces. Each can run one or several independent sequences.

[Implementation specifications](docs/SPEC.md) describe action derivation, observation reuse, failure handling, and ownership. The public API is in `org.testar.asm.playwright`:

| Component | Purpose |
| --- | --- |
| `PlaywrightExploration` | Start exploration and retain its ASM, driver, and recorder for inspection. |
| `ExplorationOptions` | Configure matching/ASMs, existing traces, action derivation, filtering, readiness, and evidence. |
| `ActionDerivationOptions` | Configure input values, excluded selectors, link scope, and action derivation limits. |
| `BrowserAction` | Describe and execute an available click, fill, or `go_back`. |
| `PlaywrightExplorationDriver` | Observe, derive, check applicability, execute, and supply recorded evidence to the ASM. |
| `ExplorationResult` | Stop response, abstract model, and evidence directory. |

Remaining work is tracked in [TODO](TODO.md).
