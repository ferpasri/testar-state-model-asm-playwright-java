package org.testar.asm.playwright;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.testar.asm.EvidenceSources;
import org.testar.asm.ExplorationDriver;
import org.testar.evidence.AvailableActionScope;
import org.testar.evidence.Observation;
import org.testar.evidence.playwright.AvailableAction;
import org.testar.evidence.playwright.PlaywrightEvidence;
import static org.testar.asm.playwright.JsonData.*;

/** Connect ASM requests to browser action derivation, action execution, and concrete evidence recording. */
public final class PlaywrightExplorationDriver implements ExplorationDriver {
    private record Choice(BrowserAction action, AvailableAction description) {
    }

    private final Page page;
    private final PlaywrightEvidence recorder;
    private final ExplorationOptions options;
    private final String sourceId;
    private final Map<String, Choice> choices = new LinkedHashMap<>();
    private String observation;
    private String preparedExecution;
    private String preparedChoice;
    private ObservationUpdate nextObservation;

    public PlaywrightExplorationDriver(Page page, PlaywrightEvidence recorder, ExplorationOptions options) {
        this.page = Objects.requireNonNull(page, "page");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.options = Objects.requireNonNull(options, "options");
        if (!recorder.isFor(page)) {
            throw new IllegalArgumentException("The recorder must belong to the supplied page");
        }
        sourceId = recorder.runId();
    }

    /** Discard a cached result context when a caller has executed more script steps between runs. */
    public void refreshOnNextObservation() {
        nextObservation = null;
    }

    /** Reuse an action's resulting observation instead of capturing it again for selection. */
    @Override
    public ObservationUpdate observe() throws Exception {
        if (nextObservation != null) {
            ObservationUpdate result = nextObservation;
            nextObservation = null;
            return result;
        }
        return observeCurrent();
    }

    private ObservationUpdate observeCurrent() throws Exception {
        options.waitUntilReady.await(page);
        String captured = recorder.capture();
        List<BrowserAction> actions = new ArrayList<>();
        for (BrowserAction action : options.deriveActions.deriveActions(page, options.actionDerivationOptions)) {
            if (!recorder.isFor(action.ownerPage())) {
                throw new IllegalArgumentException("Derived actions must belong to the supplied page");
            }
            if (options.allowAction.test(action)) {
                actions.add(action);
            }
        }
        if (actions.isEmpty() && options.stateWithoutActions == StateWithoutActions.GO_BACK) {
            BrowserAction back = BrowserAction.goBack(page);
            if (back.applicable() && options.allowAction.test(back)) {
                actions.add(back);
            }
        }
        List<AvailableAction> descriptions = actions.stream()
            .map(action -> new AvailableAction(action.actionType(), action.name(), action.locator())).toList();
        // Default action derivation returns capture-compatible paths in its single browser call.
        // Custom locators fall back to the evidence adapter's native target resolution.
        boolean knownPaths = actions.stream().allMatch(action -> action.targetPath() != null || action.locator() == null);
        List<String> paths = knownPaths ? actions.stream().map(BrowserAction::targetPath).toList() : null;
        recorder.recordAvailableActions(descriptions, AvailableActionScope.ELIGIBLE, captured, paths);
        recorder.commitCapture();
        var evidence = recorder.recordedEvidence();
        Observation recorded = evidence.observation(captured).orElseThrow();
        JsonNode source = EvidenceSources.fromEvidence(evidence, sourceId);
        var offered = array();
        choices.clear();
        for (int index = 0; index < actions.size(); index++) {
            BrowserAction action = actions.get(index);
            AvailableAction description = descriptions.get(index);
            String id = description.description().id();
            var bindings = recorded.availableActions().stream()
                .filter(binding -> binding.actionDescription().equals(id)).toList();
            if (bindings.size() != 1 || choices.containsKey(id)) {
                continue;
            }
            String target = bindings.get(0).targetWidget();
            // An uncaptured widget cannot supply a model-based target; keep its evidence.
            if (target == null && !action.actionType().equals("go_back")) {
                continue;
            }
            choices.put(id, new Choice(action, description));
            offered.add(object("choice_id", id, "action_description", id, "target_widget", target));
        }
        observation = captured;
        return new ObservationUpdate(object("context_id", UUID.randomUUID().toString(), "observation",
            object("source", sourceId, "run", recorder.runId(), "observation", captured), "choices", offered),
            array(source), selectedRuns());
    }

    /** Refresh vanished or changed controls before creating any execution evidence. */
    @Override
    public JsonNode prepareExecution(JsonNode selection) throws Exception {
        Choice choice = requireChoice(selection);
        try {
            if (!choice.action().applicable()) {
                return null;
            }
        } catch (PlaywrightException error) {
            if (page.isClosed()) {
                throw error;
            }
            return null;
        }
        Map<String, String> parameters = choice.action().actionType().equals("fill")
            ? Map.of("value", choice.action().value()) : Map.of();
        preparedExecution = recorder.selectRecordedAction(choice.description(), observation, parameters);
        preparedChoice = selection.get("choice_id").asText();
        return object("source", sourceId, "run", recorder.runId(), "execution", preparedExecution);
    }

    /** Record one selected browser operation and capture its resulting state with available actions. */
    @Override
    public ExecutionUpdate execute(JsonNode selection, JsonNode execution) throws Exception {
        Choice choice = requireChoice(selection);
        if (preparedExecution == null || !preparedExecution.equals(execution.path("execution").asText())
                || !preparedChoice.equals(selection.path("choice_id").asText())
                || !sourceId.equals(execution.path("source").asText())
                || !recorder.runId().equals(execution.path("run").asText())) {
            throw new IllegalArgumentException("Execute the prepared selection and execution reference");
        }
        try {
            recorder.executeSelected(() -> choice.action().execute(options.actionTimeout));
        } catch (PlaywrightException error) {
            // The recorder has retained the operation failure. Reassess the current GUI.
            if (page.isClosed()) {
                JsonNode source = EvidenceSources.fromEvidence(recorder.recordedEvidence(), sourceId);
                return new ExecutionUpdate(array(source), selectedRuns());
            }
        }
        preparedExecution = null;
        preparedChoice = null;
        nextObservation = observeCurrent();
        return new ExecutionUpdate(nextObservation.sources(), nextObservation.selectedRuns());
    }

    private Choice requireChoice(JsonNode selection) {
        Choice result = choices.get(selection.path("choice_id").asText());
        if (result == null) {
            throw new IllegalArgumentException("Selection must identify a currently offered action");
        }
        return result;
    }

    private JsonNode selectedRuns() {
        return array(object("source", sourceId, "run", recorder.runId()));
    }
}
