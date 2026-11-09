package org.testar.asm.playwright;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.Page;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testar.evidence.EvidenceJson;

/** Derive actions for main-document controls in one browser-side operation. */
public final class ActionDerivation {
    private static final String SCRIPT = script();

    private ActionDerivation() {
    }

    public static List<BrowserAction> deriveActions(Page page, ActionDerivationOptions options) {
        JsonNode derivedActions = EvidenceJson.tree(page.evaluate(SCRIPT, Map.of(
            "inputSelectors", new ArrayList<>(options.inputValues().keySet()),
            "excluded", options.excludedSelectors(), "sameOrigin", options.sameOriginOnly(), "limit", options.maxActions())));
        List<BrowserAction> actions = new ArrayList<>();
        for (JsonNode item : derivedActions) {
            String type = item.get("type").asText();
            actions.add(new BrowserAction(type, item.get("name").asText(), page.locator(item.get("selector").asText()),
                type.equals("fill") ? options.inputValues().get(item.get("inputSelector").asText()) : null,
                item.get("signature").asText(), item.get("path").asText(), null));
        }
        return List.copyOf(actions);
    }

    private static String script() {
        try (InputStream input = ActionDerivation.class.getResourceAsStream("derive_actions.js")) {
            if (input == null) {
                throw new IllegalStateException("Missing action derivation resource");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load action derivation", error);
        }
    }
}
