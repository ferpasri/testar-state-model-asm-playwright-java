package org.testar.asm.playwright.benchmark;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Serve the same alternating click/fill page and widget data used by the Python benchmark. */
final class BenchmarkFixture implements AutoCloseable {
    private final HttpServer server;

    BenchmarkFixture(Path output, int widgets, int properties, int states) throws IOException {
        String template = Files.readString(Path.of("benchmarks", "fixture.html"));
        StringBuilder controls = new StringBuilder();
        for (int widget = 0; widget < widgets; widget++) {
            controls.append("<div class=\"widget\"");
            for (int property = 0; property < properties; property++) {
                controls.append(" data-property-").append(property).append("=\"widget-")
                    .append(widget).append("-value-").append(property).append("\"");
            }
            controls.append("><span>Widget ").append(widget).append("</span></div>\n");
        }
        String document = template.replace("{{widgets}}", controls.toString()).replace("{{states}}", Integer.toString(states));
        Files.writeString(output.resolve("fixture.html"), document);
        byte[] content = document.getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, content.length);
            try (var response = exchange.getResponseBody()) {
                response.write(content);
            }
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
