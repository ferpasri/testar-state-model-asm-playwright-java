package org.testar.asm.playwright;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import org.testar.evidence.playwright.CaptureOptions;
import org.testar.evidence.playwright.EvidenceOptions;
import org.testar.evidence.playwright.PlaywrightEvidence;

/** Optional action derivation, evidence, and execution settings for one supplied page. */
public final class ExplorationOptions {
    @FunctionalInterface
    public interface ActionDeriver {
        List<BrowserAction> deriveActions(Page page, ActionDerivationOptions options) throws Exception;
    }

    @FunctionalInterface
    public interface Readiness {
        void await(Page page) throws Exception;
    }

    final JsonNode configuration;
    final ActionDerivationOptions actionDerivationOptions;
    final ActionDeriver deriveActions;
    final Predicate<BrowserAction> allowAction;
    final Readiness waitUntilReady;
    final double actionTimeout;
    final StateWithoutActions stateWithoutActions;
    final EvidenceOptions evidenceOptions;
    final PlaywrightEvidence recorder;
    final List<Path> existingTraces;
    final IntUnaryOperator chooseIndex;

    private ExplorationOptions(Builder builder) {
        configuration = Objects.requireNonNull(builder.configuration, "configuration").deepCopy();
        actionDerivationOptions = Objects.requireNonNull(builder.actionDerivationOptions, "actionDerivationOptions");
        deriveActions = Objects.requireNonNull(builder.deriveActions, "deriveActions");
        allowAction = Objects.requireNonNull(builder.allowAction, "allowAction");
        waitUntilReady = Objects.requireNonNull(builder.waitUntilReady, "waitUntilReady");
        if (!Double.isFinite(builder.actionTimeout) || builder.actionTimeout <= 0) {
            throw new IllegalArgumentException("actionTimeout must be a positive number of milliseconds");
        }
        actionTimeout = builder.actionTimeout;
        stateWithoutActions = Objects.requireNonNull(builder.stateWithoutActions, "stateWithoutActions");
        evidenceOptions = Objects.requireNonNull(builder.evidenceOptions, "evidenceOptions");
        recorder = builder.recorder;
        existingTraces = List.copyOf(builder.existingTraces);
        chooseIndex = Objects.requireNonNull(builder.chooseIndex, "chooseIndex");
    }

    public static ExplorationOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Set only the options needed by the application; ordinary exploration uses the defaults. */
    public static final class Builder {
        private JsonNode configuration = PlaywrightExploration.defaultConfiguration(20);
        private ActionDerivationOptions actionDerivationOptions = ActionDerivationOptions.defaults();
        private ActionDeriver deriveActions = ActionDerivation::deriveActions;
        private Predicate<BrowserAction> allowAction = action -> true;
        private Readiness waitUntilReady = page -> page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        private double actionTimeout = 5000;
        private StateWithoutActions stateWithoutActions = StateWithoutActions.GO_BACK;
        private EvidenceOptions evidenceOptions = new EvidenceOptions(Path.of("target", "traces"), "application",
            "testar-state-model-asm-playwright-java", null, CaptureOptions.defaults());
        private PlaywrightEvidence recorder;
        private List<Path> existingTraces = List.of();
        private IntUnaryOperator chooseIndex = size -> ThreadLocalRandom.current().nextInt(size);

        /** Supply abstraction rules, ASMs, and action limits using the shared ASM configuration. */
        public Builder configuration(JsonNode value) {
            configuration = value;
            return this;
        }

        public Builder actionDerivationOptions(ActionDerivationOptions value) {
            actionDerivationOptions = value;
            return this;
        }

        /** Replace default action derivation, for example when a testing tool already derives actions. */
        public Builder deriveActions(ActionDeriver value) {
            deriveActions = value;
            return this;
        }

        public Builder allowAction(Predicate<BrowserAction> value) {
            allowAction = value;
            return this;
        }

        /** Define when this application is ready to capture; failures propagate to the caller. */
        public Builder waitUntilReady(Readiness value) {
            waitUntilReady = value;
            return this;
        }

        public Builder actionTimeout(double milliseconds) {
            actionTimeout = milliseconds;
            return this;
        }

        public Builder stateWithoutActions(StateWithoutActions value) {
            stateWithoutActions = value;
            return this;
        }

        /** Used only when exploration must create its own recorder. */
        public Builder evidenceOptions(EvidenceOptions value) {
            evidenceOptions = value;
            return this;
        }

        /** Reuse a caller-owned recorder for this same page. */
        public Builder recorder(PlaywrightEvidence value) {
            recorder = value;
            return this;
        }

        public Builder existingTraces(List<Path> value) {
            existingTraces = value;
            return this;
        }

        /** Control tie-breaking for tests or a chosen source of random choices. */
        public Builder chooseIndex(IntUnaryOperator value) {
            chooseIndex = value;
            return this;
        }

        public ExplorationOptions build() {
            return new ExplorationOptions(this);
        }
    }
}
