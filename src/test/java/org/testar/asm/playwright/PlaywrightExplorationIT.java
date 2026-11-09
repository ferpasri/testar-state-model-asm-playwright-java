package org.testar.asm.playwright;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testar.evidence.ActionExecutionStatus;
import org.testar.evidence.AvailableActionScope;
import org.testar.evidence.EvidenceReader;
import org.testar.evidence.playwright.CaptureOptions;
import org.testar.evidence.playwright.EvidenceOptions;
import org.testar.evidence.playwright.EvidencePlaywright;
import org.testar.evidence.playwright.PlaywrightEvidence;
import static org.junit.jupiter.api.Assertions.*;

final class PlaywrightExplorationIT {
    private static LocalApplication application;
    private static Playwright playwright;
    private static Browser browser;
    private Page page;
    @TempDir
    Path directory;

    @BeforeAll
    static void startBrowserAndApplication() throws Exception {
        application = new LocalApplication();
        playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        browser = playwright.chromium().launch(launchOptions());
    }

    private static BrowserType.LaunchOptions launchOptions() {
        var options = new BrowserType.LaunchOptions().setHeadless(true);
        String channel = System.getProperty("playwright.channel");
        if (channel != null && !channel.isBlank()) {
            options.setChannel(channel);
        }
        return options;
    }

    @AfterAll
    static void stopBrowserAndApplication() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
        if (application != null) {
            application.close();
        }
    }

    @BeforeEach
    void openPage() {
        page = browser.newPage();
        page.setDefaultTimeout(2000);
    }

    @AfterEach
    void closePage() {
        page.close();
    }

    private static CaptureOptions capture(boolean screenshots) {
        CaptureOptions value = CaptureOptions.defaults();
        return new CaptureOptions(value.maxWidgets(), value.maxValueCharacters(), value.maxTotalCharacters(),
            value.maxAttributesPerWidget(), value.computedStyles(), value.excludedAttributes(), screenshots);
    }

    private ExplorationOptions.Builder options(int actions) {
        return ExplorationOptions.builder().configuration(PlaywrightExploration.defaultConfiguration(actions))
            .evidenceOptions(new EvidenceOptions(directory, "local-application", null, null, capture(false)))
            .actionDerivationOptions(new ActionDerivationOptions(Map.of("#name", "Alice"), List.of()))
            .chooseIndex(size -> 0).stateWithoutActions(StateWithoutActions.STOP);
    }

    @Test
    void emptyPagesWithoutBackwardHistoryStopNormally() throws Exception {
        page.navigate(application.url("/done"));
        var result = PlaywrightExploration.explore(page,
            options(1).stateWithoutActions(StateWithoutActions.GO_BACK).build());
        assertEquals("no_available_choices", result.stopped().get("reason").asText());
        assertEquals(0, EvidenceReader.read(result.evidenceDirectory()).actionExecutions().size());
    }

    @Test
    void actionDerivationCannotExecuteAnActionOnAnotherPage() throws Exception {
        page.navigate(application.url("/home"));
        try (Page other = browser.newPage()) {
            other.setContent("<button>Other page</button>");
            assertThrows(IllegalArgumentException.class, () -> PlaywrightExploration.explore(page,
                options(1).deriveActions((current, ignored) ->
                    List.of(new BrowserAction("click", "Other", other.locator("button")))).build()));
        }
    }

    @Test
    void anUnexpectedReadinessFailureRetainsTheOutstandingAsmExecution() throws Exception {
        page.navigate(application.url("/home"));
        AtomicInteger waits = new AtomicInteger();
        try (var exploration = new PlaywrightExploration(page, options(1).waitUntilReady(current -> {
            if (waits.incrementAndGet() > 1) {
                throw new IllegalStateException("Result page not ready");
            }
        }).build())) {
            assertThrows(IllegalStateException.class, exploration::run);
            assertEquals("execution_in_progress", exploration.asm().select().get("reason").asText());
            // The browser call returned, but its result state has not been supplied to the ASM.
            assertEquals(ActionExecutionStatus.AWAITING_RESULTING_OBSERVATION,
                exploration.recorder().recordedEvidence().actionExecutions().get(0).status());
        }
    }

    @Test
    void aScriptCanLoginThenHandItsPageToExploration() throws Exception {
        page.navigate(application.url("/login"));
        page.locator("#user").fill("demo");
        page.locator("#signin").click();
        // Action derivation supplies Open form, filling name, and the newly enabled Submit.
        ExplorationResult result = PlaywrightExploration.explore(page, options(10).build());
        assertEquals("no_available_choices", result.stopped().get("reason").asText());
        assertTrue(page.url().endsWith("/done"));
        var trace = EvidenceReader.read(result.evidenceDirectory());
        assertEquals(3, trace.actionExecutions().size());
        assertEquals(3, trace.recordedTransitions().size());
        assertEquals(4, trace.observations().size());
        assertTrue(trace.actionExecutions().stream().allMatch(record -> record.status() == ActionExecutionStatus.COMPLETED));
        assertTrue(trace.observations().stream().allMatch(record -> record.availableActionScope() == AvailableActionScope.ELIGIBLE));
        assertTrue(trace.testRuns().get(0).finished());
        assertFalse(page.isClosed());
    }

    @Test
    void actionDerivationRespectsVisibilityInputValuesAndExcludedControls() {
        page.navigate(application.url("/controls"));
        var values = new ActionDerivationOptions(Map.of("#configured", "text", "#secret", "private-value", "#readonly", "ignored"),
            List.of("#exclude"));
        List<BrowserAction> actions = ActionDerivation.deriveActions(page, values);
        assertEquals(List.of("click", "fill", "fill"), actions.stream().map(BrowserAction::actionType).toList());
        assertEquals(List.of("Click visible", "Fill configured", "Fill secret"), actions.stream().map(BrowserAction::name).toList());
        assertFalse(actions.toString().contains("private-value"));
        assertEquals(1, ActionDerivation.deriveActions(page, new ActionDerivationOptions(Map.of(), List.of(), true, 1)).size());
    }

    @Test
    void stoppingAtTheActionLimitRetainsTheNextAvailableActions() throws Exception {
        page.navigate(application.url("/home"));
        var result = PlaywrightExploration.explore(page, options(1).build());
        assertEquals("action_limit_reached", result.stopped().get("reason").asText());
        var trace = EvidenceReader.read(result.evidenceDirectory());
        assertEquals(1, trace.actionExecutions().size());
        var last = trace.observations().get(trace.observations().size() - 1);
        assertEquals(1, last.availableActions().size());
        assertNotEquals(trace.actionExecutions().get(0).actionExecution().actionDescription(),
            last.availableActions().get(0).actionDescription());
        assertEquals(2, trace.observations().size());
    }

    @Test
    void disappearedAndReplacedControlsRequestARefreshWithoutAnExecution() throws Exception {
        page.navigate(application.url("/home"));
        try (var exploration = new PlaywrightExploration(page, options(1).build())) {
            var update = exploration.driver().observe();
            exploration.asm().updateEvidence("initial", update.sources(), update.selectedRuns());
            exploration.asm().setContext(update.context());
            var selected = exploration.asm().select();
            // The same DOM path now describes another control, so this selection is stale.
            page.locator("#next").evaluate("element => element.textContent = 'Different control'");
            assertNull(exploration.driver().prepareExecution(selected));
            page.locator("#next").evaluate("element => element.remove()");
            assertNull(exploration.driver().prepareExecution(selected));
            assertEquals(0, exploration.recorder().recordedEvidence().actionExecutions().size());
        }
    }

    @Test
    void browserOperationFailuresRemainRecordedAndExplorationCanReassessThePage() throws Exception {
        page.navigate(application.url("/home"));
        try (var exploration = new PlaywrightExploration(page, options(1).actionTimeout(100).build())) {
            var update = exploration.driver().observe();
            exploration.asm().updateEvidence("initial", update.sources(), update.selectedRuns());
            exploration.asm().setContext(update.context());
            var selected = exploration.asm().select();
            var execution = exploration.driver().prepareExecution(selected);
            exploration.asm().beginExecution(selected, execution);
            // Simulate a control disappearing after the applicability check.
            page.locator("#next").evaluate("element => element.remove()");
            var reported = exploration.driver().execute(selected, execution);
            exploration.asm().reportExecution(execution, "failed", reported.sources(), reported.selectedRuns());
            var trace = exploration.recorder().recordedEvidence();
            assertEquals(ActionExecutionStatus.FAILED, trace.actionExecutions().get(0).status());
            assertEquals(2, trace.observations().size());
            assertEquals(0, trace.recordedTransitions().size());
        }
    }

    @Test
    void emptyXmlPagesCanOfferGoBackThroughTheAsm() throws Exception {
        page.navigate(application.url("/home"));
        page.navigate(application.url("/empty.wsdl"));
        var result = PlaywrightExploration.explore(page,
            options(1).stateWithoutActions(StateWithoutActions.GO_BACK).build());
        assertTrue(page.url().endsWith("/home"));
        var trace = EvidenceReader.read(result.evidenceDirectory());
        var execution = trace.actionExecutions().get(0).actionExecution();
        assertEquals("go_back", trace.actionDescription(execution.actionDescription()).orElseThrow().actionType());
        assertNull(execution.targetWidget());
        assertEquals(1, trace.recordedTransitions().size());
    }

    @Test
    void stopPolicyAndActionFiltersCanLeaveThePageWithoutASelection() throws Exception {
        page.navigate(application.url("/home"));
        page.navigate(application.url("/done"));
        var result = PlaywrightExploration.explore(page,
            options(10).stateWithoutActions(StateWithoutActions.GO_BACK).allowAction(action -> false).build());
        assertEquals("no_available_choices", result.stopped().get("reason").asText());
        assertEquals(0, EvidenceReader.read(result.evidenceDirectory()).actionExecutions().size());
        assertTrue(page.url().endsWith("/done"));
    }

    @Test
    void existingTracesMakeThePreviouslyUnexecutedActionTheNextChoice() throws Exception {
        page.navigate(application.url("/choices"));
        var first = PlaywrightExploration.explore(page, options(1).build());
        var original = EvidenceReader.read(first.evidenceDirectory());
        var firstId = original.actionExecutions().get(0).actionExecution().actionDescription();
        String firstName = original.actionDescription(firstId).orElseThrow().properties().get("playwright.description").asText();
        assertTrue(List.of("Click used", "Click new").contains(firstName));

        page.navigate(application.url("/choices"));
        var second = PlaywrightExploration.explore(page, options(1).existingTraces(List.of(first.evidenceDirectory())).build());
        var enriched = EvidenceReader.read(second.evidenceDirectory());
        var secondId = enriched.actionExecutions().get(0).actionExecution().actionDescription();
        // Either tied action can be first; recorded history makes the other one unvisited.
        String secondName = enriched.actionDescription(secondId).orElseThrow().properties().get("playwright.description").asText();
        assertEquals(firstName.equals("Click used") ? "Click new" : "Click used", secondName);
    }

    @Test
    void customActionDerivationCanSupplyNativeLocators() throws Exception {
        page.navigate(application.url("/home"));
        var result = PlaywrightExploration.explore(page, options(1)
            .deriveActions((current, ignored) -> List.of(new BrowserAction("click", "Open the form", current.locator("#next")))).build());
        var trace = EvidenceReader.read(result.evidenceDirectory());
        assertNotNull(trace.actionExecutions().get(0).actionExecution().targetWidget());
        assertTrue(page.url().endsWith("/form"));
    }

    @Test
    void uncapturedControlsRemainEvidenceButCannotBecomeModelBasedChoices() throws Exception {
        page.navigate(application.url("/home"));
        var small = new CaptureOptions(1, 8192, 4194304, 256, List.of(), List.of(), false);
        var result = PlaywrightExploration.explore(page, options(1)
            .evidenceOptions(new EvidenceOptions(directory, "small-capture", null, null, small)).build());
        var trace = EvidenceReader.read(result.evidenceDirectory());
        assertEquals(0, trace.actionExecutions().size());
        assertEquals(1, trace.observations().get(0).availableActions().size());
        assertNull(trace.observations().get(0).availableActions().get(0).targetWidget());
    }

    @Test
    void filledPasswordsAreRedactedByTheEvidenceAdapter() throws Exception {
        page.navigate(application.url("/secret"));
        var result = PlaywrightExploration.explore(page, options(1)
            .actionDerivationOptions(new ActionDerivationOptions(Map.of("#secret", "private-password-value"), List.of())).build());
        String jsonl = Files.readString(result.evidenceDirectory().resolve("evidence.jsonl"));
        assertFalse(jsonl.contains("private-password-value"));
        var trace = EvidenceReader.read(result.evidenceDirectory());
        assertTrue(trace.actionExecutions().get(0).actionExecution().parameters().contains("playwright.redacted_parameters"));
    }

    @Test
    void readinessAndInvalidSelectorErrorsPropagateToTheCaller() {
        page.navigate(application.url("/home"));
        assertThrows(IllegalStateException.class, () -> PlaywrightExploration.explore(page, options(1)
            .waitUntilReady(current -> { throw new IllegalStateException("Application not ready"); }).build()));
        assertThrows(com.microsoft.playwright.PlaywrightException.class, () -> PlaywrightExploration.explore(page,
            options(1).actionDerivationOptions(new ActionDerivationOptions(Map.of("[", "value"), List.of())).build()));
    }

    @Test
    void facadePreparationAndExplorationShareOneRecorderWithoutDuplicateExecutions() throws Exception {
        var evidenceOptions = new EvidenceOptions(directory, "facade", null, null, capture(false));
        try (Playwright recordedPlaywright = EvidencePlaywright.create(evidenceOptions,
                new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             Browser recordedBrowser = recordedPlaywright.chromium().launch(launchOptions())) {
            Page recordedPage = recordedBrowser.newPage();
            recordedPage.navigate(application.url("/login"));
            recordedPage.locator("#user").fill("demo");
            recordedPage.locator("#signin").click();
            Path traceDirectory;
            try (var exploration = new PlaywrightExploration(recordedPage, options(10).build())) {
                assertSame(EvidencePlaywright.evidenceFor(recordedPage), exploration.recorder());
                traceDirectory = exploration.run().evidenceDirectory();
                assertEquals(6, exploration.recorder().recordedEvidence().actionExecutions().size());
            }
            // The facade still owns its recorder after the exploration helper closes.
            recordedPage.navigate(application.url("/home"));
            recordedBrowser.close();
            var trace = EvidenceReader.read(traceDirectory);
            assertEquals(7, trace.actionExecutions().size());
            assertEquals(1, trace.testRuns().size());
            assertTrue(trace.testRuns().get(0).finished());
        }
    }

    @Test
    void explicitRecordersRemainUnderTheirCallersOwnershipAndRejectAnotherPage() throws Exception {
        page.navigate(application.url("/home"));
        try (var recorder = PlaywrightEvidence.start(directory.resolve("explicit"), page, capture(false))) {
            var result = PlaywrightExploration.explore(page, options(1).recorder(recorder).build());
            recorder.capture();
            recorder.commitCapture();
            assertEquals(3, recorder.recordedEvidence().observations().size());
            try (Page other = browser.newPage()) {
                assertThrows(IllegalArgumentException.class, () ->
                    new PlaywrightExploration(other, options(1).recorder(recorder).build()));
            }
            assertEquals(recorder.directory(), result.evidenceDirectory());
        }
    }

    @Test
    void screenshotsAreSavedAndVerifiedWhenEnabled() throws Exception {
        page.navigate(application.url("/home"));
        var result = PlaywrightExploration.explore(page, options(1)
            .evidenceOptions(new EvidenceOptions(directory, "screenshots", null, null, capture(true))).build());
        var trace = EvidenceReader.read(result.evidenceDirectory());
        assertTrue(trace.observations().stream().allMatch(observation -> observation.artifacts().size() == 1));
        for (var observation : trace.observations()) {
            assertTrue(Files.isRegularFile(result.evidenceDirectory().resolve(observation.artifacts().get(0).path())));
        }
    }
}
