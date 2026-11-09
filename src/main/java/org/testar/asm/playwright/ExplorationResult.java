package org.testar.asm.playwright;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;

/** Stop response, abstract model, and concrete evidence directory produced by exploration. */
public record ExplorationResult(JsonNode stopped, JsonNode model, Path evidenceDirectory) {
}
