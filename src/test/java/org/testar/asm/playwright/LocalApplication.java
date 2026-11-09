package org.testar.asm.playwright;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** A small local GUI makes action derivation and selection tests repeatable without a public website. */
final class LocalApplication implements AutoCloseable {
    private final HttpServer server;

    LocalApplication() throws IOException {
        Map<String, String> pages = Map.of(
            "/login", "<title>Login</title><input id='user'><button id='signin' onclick=\"location.href='/home'\">Sign in</button>",
            "/home", "<title>Home</title><button id='next' onclick=\"location.href='/form'\">Open form</button>",
            "/form", """
                <title>Form</title><input id="name" oninput="document.querySelector('#submit').disabled = !this.value">
                <button id="submit" disabled onclick="location.href='/done'">Submit</button>
                """,
            "/done", "<title>Done</title><p>Completed</p>",
            "/controls", """
                <title>Controls</title><button id="visible">Visible</button><button hidden>Hidden</button>
                <button disabled>Disabled</button><button id="exclude">Excluded</button>
                <input id="configured"><input id="unsupplied"><input id="secret" type="password">
                <input id="readonly" readonly><a href="https://example.invalid/">External</a>
                <a href="/home" target="_blank">New tab</a><a download href="/home">Download</a>
                """,
            "/choices", "<title>Choices</title><button id='used'>Used</button><button id='new'>New</button>",
            "/secret", "<title>Secret</title><input id='secret' type='password'>",
            "/empty.wsdl", "<?xml version='1.0'?><definitions xmlns='http://schemas.xmlsoap.org/wsdl/'/>");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] content = pages.getOrDefault(exchange.getRequestURI().getPath(), "<title>Missing</title>")
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", exchange.getRequestURI().getPath().endsWith(".wsdl")
                ? "application/xml" : "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, content.length);
            try (var output = exchange.getResponseBody()) {
                output.write(content);
            }
        });
        server.start();
    }

    String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
