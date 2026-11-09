package org.testar.asm.playwright;

import com.microsoft.playwright.Locator;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ExplorationOptionsTest {
    @Test
    void customExampleConfigurationIsAcceptedByTheNativeAsm() {
        var configuration = org.testar.asm.playwright.examples.ParabankCustomAsm.configuration(20);
        var empty = org.testar.evidence.EvidenceJson.parse("[]");
        var run = new org.testar.asm.AsmRun(configuration, empty, empty);
        assertNotNull(run.model());
    }

    @Test
    void defaultConfigurationUsesUnvisitedThenRandomWithOneOverallLimit() {
        var configuration = PlaywrightExploration.defaultConfiguration(20);
        assertEquals("unvisited", configuration.get("asms").get(0).get("name").asText());
        assertEquals("random", configuration.get("asms").get(1).get("name").asText());
        assertEquals(20, configuration.get("total_action_limit").asInt());
        assertThrows(IllegalArgumentException.class, () -> PlaywrightExploration.defaultConfiguration(0));
        assertThrows(IllegalArgumentException.class, () -> ExplorationOptions.builder().actionTimeout(Double.NaN).build());
    }

    @Test
    void inputConfigurationIsCopiedAndItsValuesStayOutOfObjectDescriptions() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("#password", "private-value");
        ActionDerivationOptions options = new ActionDerivationOptions(values, List.of("#logout"));
        values.put("#extra", "later");
        assertEquals(1, options.inputValues().size());
        assertFalse(options.toString().contains("private-value"));
        assertThrows(UnsupportedOperationException.class, () -> options.inputValues().clear());
        Locator locator = (Locator) Proxy.newProxyInstance(Locator.class.getClassLoader(), new Class<?>[]{Locator.class},
            (proxy, method, arguments) -> "locator");
        assertFalse(new BrowserAction("fill", "Fill password", locator, "private-value").toString().contains("private-value"));
    }

    @Test
    void invalidActionDerivationAndActionDescriptionsAreRejectedBeforeBrowserWork() {
        assertThrows(IllegalArgumentException.class, () -> new ActionDerivationOptions(Map.of(), List.of(), true, 0));
        assertThrows(IllegalArgumentException.class, () -> new ActionDerivationOptions(Map.of("", "value"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ActionDerivationOptions(Map.of(), List.of(" ")));
        assertThrows(IllegalArgumentException.class, () -> new BrowserAction("unknown", "Action", null));
        assertThrows(NullPointerException.class, () -> new BrowserAction("click", "Click", null));
    }
}
