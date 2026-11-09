package org.testar.asm.playwright;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bound action derivation and provide explicit values for fields the ASM may fill. */
public record ActionDerivationOptions(Map<String, String> inputValues, List<String> excludedSelectors,
                               boolean sameOriginOnly, int maxActions) {
    public ActionDerivationOptions {
        Objects.requireNonNull(inputValues, "inputValues");
        inputValues.forEach((selector, value) -> {
            if (selector == null || selector.isBlank() || value == null) {
                throw new IllegalArgumentException("Input values must map CSS selectors to strings");
            }
        });
        inputValues = Collections.unmodifiableMap(new LinkedHashMap<>(inputValues));
        excludedSelectors = List.copyOf(Objects.requireNonNull(excludedSelectors, "excludedSelectors"));
        if (excludedSelectors.stream().anyMatch(String::isBlank) || maxActions < 1 || maxActions > 5000) {
            throw new IllegalArgumentException("Use nonblank excluded selectors and maxActions between 1 and 5000");
        }
    }

    public ActionDerivationOptions(Map<String, String> inputValues, List<String> excludedSelectors) {
        this(inputValues, excludedSelectors, true, 200);
    }

    public static ActionDerivationOptions defaults() {
        return new ActionDerivationOptions(Map.of(), List.of());
    }

    /** Do not expose configured field values in logs or debugger object descriptions. */
    @Override
    public String toString() {
        return "ActionDerivationOptions[inputs=" + inputValues.size() + ", exclusions=" + excludedSelectors.size()
            + ", sameOriginOnly=" + sameOriginOnly + ", maxActions=" + maxActions + "]";
    }
}
