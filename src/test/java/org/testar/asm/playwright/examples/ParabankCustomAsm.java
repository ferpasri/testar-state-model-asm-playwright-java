package org.testar.asm.playwright.examples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testar.asm.playwright.ActionDerivationOptions;
import org.testar.asm.playwright.ExplorationOptions;
import org.testar.asm.playwright.ExplorationResult;
import org.testar.asm.playwright.PlaywrightExploration;
import org.testar.asm.playwright.StateWithoutActions;
import org.testar.evidence.EvidenceJson;
import org.testar.evidence.playwright.CaptureOptions;
import org.testar.evidence.playwright.EvidenceOptions;
import org.testar.evidence.playwright.EvidencePlaywright;

/** Standalone example: custom abstraction and optional existing traces. */
public final class ParabankCustomAsm {
    private ParabankCustomAsm() {
    }

    public static void main(String[] options) throws Exception {
        Arguments arguments = Arguments.parse(options);
        if (arguments == null) {
            return;
        }
        // These optional labels describe evidence traces; they do not control ASM selection.
        var evidence = new EvidenceOptions(arguments.evidencePath, "parabank", null,
            "Explore using custom abstraction and existing traces", CaptureOptions.defaults());
        // The evidence facade also records scripted login before exploration begins.
        try (Playwright playwright = EvidencePlaywright.create(evidence);
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(arguments.headless))) {
            for (int sequence = 1; sequence <= arguments.sequences; sequence++) {
                System.out.println("Sequence " + sequence + " of " + arguments.sequences);
                ExplorationResult result = runSequence(browser, arguments);
                System.out.println("Stop reason: " + result.stopped().get("reason").asText());
                System.out.println("Evidence directory: " + result.evidenceDirectory());
            }
        }
    }

    /** Each sequence has fresh browser cookies/storage and an independent ASM run. */
    private static ExplorationResult runSequence(Browser browser, Arguments arguments) throws Exception {
        // A fresh context does not reset data stored on the application's server.
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            page.setDefaultTimeout(15000);
            page.navigate(arguments.url);
            page.locator("input[name='username']").fill("john");
            page.locator("input[name='password']").fill("demo");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Log In").setExact(true)).click();
            // This is a readiness wait, rather than a recorded oracle evaluation.
            page.getByText("Account Services", new Page.GetByTextOptions().setExact(true)).waitFor();

            // The integration derives actions; this script only supplies optional configuration.
            var exploration = ExplorationOptions.builder()
                .configuration(configuration(arguments.actions))
                .stateWithoutActions(arguments.stateWithoutActions)
                // Configured values offer fills only where these fields are found.
                .actionDerivationOptions(new ActionDerivationOptions(Map.of("#amount", "999999", "#downPayment", "1"),
                    List.of("a[href*='logout.htm']", "a[href*='admin.htm']")))
                // Supply individual trace directories. They guide selection, not browser login or restoration.
                .existingTraces(arguments.existingTraces)
                .build();
            return PlaywrightExploration.explore(page, exploration);
        }
    }

    /** Example matching decisions; choose rules appropriate to the application's views and objective. */
    public static JsonNode configuration(int actions) {
        ObjectNode configuration = (ObjectNode) PlaywrightExploration.defaultConfiguration(actions);
        ObjectNode abstraction = (ObjectNode) configuration.get("abstraction");
        abstraction.put("name", "Page content and target without session data");
        // Normalize only model comparison. Recorded URLs remain unchanged.
        ((ObjectNode) abstraction.get("state").get(0)).set("transforms", EvidenceJson.parse("""
            [{"op":"url","remove_path_parameters":["JSESSIONID_8111","jsessionid"],
              "remove_query":["session"],"remove_fragment":true}]
            """));
        // Text and CSS visibility changes can distinguish views at the same page address.
        abstraction.set("widgets", EvidenceJson.parse("""
            {"fields":[{"name":"tag","keys":["playwright.dom.tagName"]},
                       {"name":"text","keys":["ui.text"]},
                       {"name":"display","keys":["playwright.style.display"]},
                       {"name":"visibility","keys":["playwright.style.visibility"]}],
             "hierarchy":"flat","order":"sort","descendants":"exclude"}
            """));
        // Alternate up to five Unvisited and three Random actions until the overall limit.
        configuration.set("asms", EvidenceJson.parse("""
            [{"name":"unvisited","action_limit":5,"unmatched_choices":"exclude"},
             {"name":"random","action_limit":3}]
            """));
        return configuration;
    }

    /** All arguments are optional. Running main without them starts one visible 10-action sequence. */
    private static final class Arguments {
        int actions = 10;
        int sequences = 1;
        boolean headless;
        String url = "https://para.testar.org/";
        Path evidencePath = Path.of("target", "traces");
        StateWithoutActions stateWithoutActions = StateWithoutActions.GO_BACK;
        final List<Path> existingTraces = new ArrayList<>();

        static Arguments parse(String[] arguments) {
            Arguments result = new Arguments();
            for (int index = 0; index < arguments.length; index++) {
                switch (arguments[index]) {
                    case "--actions" -> result.actions = Integer.parseInt(value(arguments, ++index));
                    case "--sequences" -> result.sequences = Integer.parseInt(value(arguments, ++index));
                    case "--url" -> result.url = value(arguments, ++index);
                    case "--evidence-path" -> result.evidencePath = Path.of(value(arguments, ++index));
                    case "--headless" -> result.headless = true;
                    case "--state-without-actions" -> result.stateWithoutActions = switch (value(arguments, ++index)) {
                        case "go_back" -> StateWithoutActions.GO_BACK;
                        case "stop" -> StateWithoutActions.STOP;
                        default -> throw new IllegalArgumentException("Use go_back or stop");
                    };
                    case "--existing-trace" -> result.existingTraces.add(Path.of(value(arguments, ++index)));
                    case "--help" -> {
                        System.out.println("--actions N --sequences N --url URL --evidence-path PATH --headless"
                            + " --state-without-actions go_back|stop --existing-trace PATH");
                        return null;
                    }
                    default -> throw new IllegalArgumentException("Unknown option: " + arguments[index]);
                }
            }
            if (result.actions < 1 || result.sequences < 1) {
                throw new IllegalArgumentException("Actions and sequences must be positive integers");
            }
            return result;
        }

        private static String value(String[] arguments, int index) {
            if (index == arguments.length) {
                throw new IllegalArgumentException("Missing option value");
            }
            return arguments[index];
        }
    }

}
