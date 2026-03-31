package cz.uhk.loadtesterapp.service;

import com.sun.net.httpserver.HttpServer;
import cz.uhk.loadtesterapp.model.entity.RequestDefinition;
import cz.uhk.loadtesterapp.model.enums.HttpMethodType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiRequestServiceTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void send_ShouldMergeHeaders_AndOverrideWithMethodArgument() {
        AtomicReference<String> headerA = new AtomicReference<>();
        AtomicReference<String> headerB = new AtomicReference<>();
        AtomicReference<String> headerX = new AtomicReference<>();

        server.createContext("/merge", exchange -> {
            headerA.set(exchange.getRequestHeaders().getFirst("X-A"));
            headerB.set(exchange.getRequestHeaders().getFirst("X-B"));
            headerX.set(exchange.getRequestHeaders().getFirst("X-Override"));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
            exchange.close();
        });

        ApiRequestService service = new ApiRequestService(org.springframework.web.reactive.function.client.WebClient.create());
        RequestDefinition request = RequestDefinition.builder()
                .url(baseUrl + "/merge")
                .method(HttpMethodType.GET)
                .headers(Map.of("X-A", "one", "X-Override", "original"))
                .build();

        service.send(request, Map.of("X-B", "two", "X-Override", "replacement")).block();

        assertEquals("one", headerA.get());
        assertEquals("two", headerB.get());
        assertEquals("replacement", headerX.get());
    }

    @Test
    void send_ShouldNotAttachBody_ForGet() {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();

        server.createContext("/get", exchange -> {
            method.set(exchange.getRequestMethod());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
            exchange.close();
        });

        ApiRequestService service = new ApiRequestService(org.springframework.web.reactive.function.client.WebClient.create());
        RequestDefinition request = RequestDefinition.builder()
                .url(baseUrl + "/get")
                .method(HttpMethodType.GET)
                .body("should-not-be-sent")
                .build();

        service.send(request).block();

        assertEquals("GET", method.get());
        assertTrue(body.get().isEmpty());
    }

    @Test
    void send_ShouldDefaultContentTypeToApplicationJson_WhenMissing() {
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();

        server.createContext("/post", exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
            exchange.close();
        });

        ApiRequestService service = new ApiRequestService(org.springframework.web.reactive.function.client.WebClient.create());
        RequestDefinition request = RequestDefinition.builder()
                .url(baseUrl + "/post")
                .method(HttpMethodType.POST)
                .body("{\"ok\":true}")
                .build();

        service.send(request).block();

        assertTrue(contentType.get().startsWith("application/json"));
        assertEquals("{\"ok\":true}", body.get());
    }
}
