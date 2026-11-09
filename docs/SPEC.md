# Implementation specification

## Exploration cycle

The Java ASM library controls observation, selection, execution reporting, and stopping. This integration supplies the browser capabilities through `ExplorationDriver`. The evidence adapter and library record concrete states, available actions, execution results, and transitions. The abstraction library constructs the abstract model used for selection.

The driver waits for readiness, captures the current page, derives and filters actions, and attaches the eligible actions to that capture. It commits the observation before supplying its source-qualified references and choices to the ASM. A choice identifies an action description and its captured widget.

Before allocating an execution, the driver checks that the selected locator still identifies one visible, enabled control. Fills also require editability. Default action derivation compares the control description with the derived description, so a different control at the same DOM path requests a fresh observation. No execution is recorded for that rejected selection.

The evidence adapter executes the selected operation with nested facade recording suppressed. The driver then waits for readiness, captures its resulting state and available actions, and updates the ASM. That same observation supplies the next selection context. An ordinary exploration records one initial observation plus one resulting observation per executed action.

## Action derivation and applicability

Default action derivation examines the main document in one browser call. It offers clicks on buttons, links, checkbox/radio inputs, and explicit button/link roles. Text inputs and textareas receive fills only when their CSS selector has a configured string value. Input values stay out of action descriptions and object descriptions; the evidence adapter redacts password parameters.

Hidden, disabled, inert, excluded, and readonly fill targets are omitted. Links use HTTP or HTTPS, remain in the current browsing context, and stay on the same origin by default. Download links are omitted. `maxActions` bounds action derivation and the recorded available-action scope is eligible.

Default action derivation returns exact DOM selectors, control descriptions, and capture-compatible target paths. It adds no attributes to the application. The evidence adapter binds those paths to captured widgets. Custom action derivation supplies `BrowserAction` objects with native or facade locators; the evidence adapter resolves their targets. Actions must belong to the supplied page. Uncaptured targets remain recorded evidence but are excluded from model-based widget choices.

Action derivation covers the main document. Custom frame or shadow-root actions require captured targets that the evidence adapter can resolve. Capture, action derivation, and execution are separate operations; applicability checks do not make them atomic. Playwright still performs its native actionability checks.

## Pages without eligible actions

`StateWithoutActions.GO_BACK` is the default. When no eligible widget actions remain and backward navigation is available, the driver offers `go_back`, with no widget target. It passes through the action filter and ASM like other available actions, consumes an action count, and records its resulting state.

`StateWithoutActions.STOP` ends exploration when no eligible action remains. Without backward history, `GO_BACK` also leaves no choice. Target-based action abstraction may leave `go_back` unmatched; Random fallback in the default configuration permits its selection. A custom configuration must permit that choice.

## Configuration

`ExplorationOptions.builder()` supplies optional settings:

| Setting | Meaning |
| --- | --- |
| `configuration` | Shared abstraction and ASM/controller configuration. |
| `existingTraces` | Individual evidence directories read and validated before exploration starts. All their recorded runs are selected. |
| `actionDerivationOptions` | Explicit input values, excluded selectors, link scope, and action derivation limit. |
| `deriveActions` | Replace action derivation when a testing tool already derives actions. |
| `allowAction` | Filter derived actions, including a fallback back action. |
| `waitUntilReady` | Define application readiness before capture and action derivation. |
| `actionTimeout` | Positive timeout in milliseconds for a selected browser operation. |
| `stateWithoutActions` | Offer `GO_BACK` or `STOP` when no eligible widget actions remain. |
| `evidenceOptions` | Location, capture settings, and optional labels for an owned recorder. |
| `recorder` | Reuse an explicit recorder belonging to this page. |
| `chooseIndex` | Supply the random tie-breaking function, for example in deterministic tests. |

The default configuration uses page address/title and captured target ID, name, or DOM path. It retains URL query/session data. Applications with distinct views at the same address/title or dynamic identifiers can supply their own abstraction rules.

The default readiness wait is DOMContentLoaded. A callback requiring a page-specific element can fail on an unexpected page before the empty-action policy is reached. `run(maxCycles)` bounds observation attempts; the ASM configuration controls executed-action limits.

## Ownership and failures

`PlaywrightExploration` owns only a recorder it creates. Closing it finishes that trace while leaving the page and browser open. Explicit and evidence-facade recorders retain their original owners. Each exploration uses the supplied Playwright page on its owning thread.

Playwright operation exceptions are recorded as failed executions and the page is reassessed when available. Action derivation, readiness, and persistence errors propagate. An unexpected driver error leaves its ASM execution context available for investigation. The evidence library retains valid saved prefixes.

`run()` can continue an exploration with the same ASM. It refreshes the page rather than reusing a cached result after possible intervening script steps. The convenience `explore` call closes an owned recorder and returns `ExplorationResult`; its abstract model is independent from the concrete trace.

Selection follows [ASM Specs](https://github.com/ferpasri/testar-state-model-asm-specs). Recording follows [Evidence Specs](https://github.com/ferpasri/testar-evidence-specs), and matching follows [Abstraction Specs](https://github.com/ferpasri/testar-abstraction-specs).
