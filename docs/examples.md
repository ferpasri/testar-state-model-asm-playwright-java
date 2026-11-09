# Scripted login followed by exploration

Each example is a standalone Java file under `src/test/java/org/testar/asm/playwright/examples`. The scripted login uses the ParaBank demo account `john` / `demo`, then hands the page to the ASM. The evidence facade records both parts in the same trace.

Run from the repository root after building its dependencies and installing Chromium:

~~~powershell
mvn test-compile exec:java "-Dexec.classpathScope=test" "-Dexec.mainClass=org.testar.asm.playwright.examples.ParabankBasic"
mvn test-compile exec:java "-Dexec.classpathScope=test" "-Dexec.mainClass=org.testar.asm.playwright.examples.ParabankInputsAndFilters" "-Dexec.args=--sequences 3 --actions 20"
mvn test-compile exec:java "-Dexec.classpathScope=test" "-Dexec.mainClass=org.testar.asm.playwright.examples.ParabankCustomAsm" "-Dexec.args=--sequences 3 --actions 20 --existing-trace target/traces/YOUR_TRACE"
~~~

## Choose an example

- [ParabankBasic](../src/test/java/org/testar/asm/playwright/examples/ParabankBasic.java) uses default action derivation and Unvisited/Random selection.
- [ParabankInputsAndFilters](../src/test/java/org/testar/asm/playwright/examples/ParabankInputsAndFilters.java) offers loan amount/down-payment fills and excludes logout and administration links.
- [ParabankCustomAsm](../src/test/java/org/testar/asm/playwright/examples/ParabankCustomAsm.java) normalizes session data, compares captured text/styles, alternates Unvisited and Random, and optionally loads existing traces.

The custom rules are example decisions. Choose an abstraction that distinguishes the application views relevant to the exploration objective. Existing traces contribute recorded knowledge; they do not restore a browser session.

## Optional arguments

| Argument | Default and meaning |
| --- | --- |
| `--url URL` | ParaBank demo address opened before login. |
| `--actions N` | 10 executed ASM actions per sequence; scripted login is outside that count. |
| `--sequences N` | 1 independent sequence. Each uses a fresh browser context and ASM. |
| `--headless` | Flag hiding the browser window; otherwise the browser is visible. |
| `--evidence-path PATH` | `target/traces`, the parent for unique timestamped traces. |
| `--state-without-actions MODE` | `go_back` offers browser navigation when possible; `stop` ends that sequence. |
| `--existing-trace PATH` | Custom example only. Repeat for several individual trace directories; omit to start with an empty model. |
| `--help` | Print the supported arguments without opening a browser. |

A fresh browser context clears cookies and storage, not server-side application data. Evidence is written incrementally. Closing each context finishes its trace, whose directory is printed after the sequence. Optional SUT and test objective labels describe evidence; they do not control ASM selection.

The examples compile during normal Maven builds. Browser integration tests use a separate local application, so testing the library does not depend on the public ParaBank service.
