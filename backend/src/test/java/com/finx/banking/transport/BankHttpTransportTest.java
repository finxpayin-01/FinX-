package com.finx.banking.transport;

import com.finx.banking.signing.HmacSha256BankRequestSigner;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BankHttpTransportTest {

    private HttpServer server;
    private String serverUrl;
    private final AtomicInteger requestCounter = new AtomicInteger(0);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(null);
        server.start();
        int port = server.getAddress().getPort();
        serverUrl = "http://127.0.0.1:" + port;
        requestCounter.set(0);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Successful request returns status, body and headers")
    void testSuccessfulRequest() {
        server.createContext("/api/v1/test", exchange -> {
            requestCounter.incrementAndGet();
            byte[] response = "{\"status\": \"OK\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        WebClient webClient = WebClient.builder().baseUrl(serverUrl).build();
        BankHttpTransport transport = new BankHttpTransport(webClient, "PARTNER_A", "client-1");

        BankHttpTransport.TransportResponse resp = transport.execute(
                HttpMethod.POST,
                "/api/v1/test",
                "{\"hello\": \"world\"}",
                "FINX-REL-OP-1"
        );

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("OK");
        assertThat(requestCounter.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("HTTP 401/403 maps to BankAuthenticationException without retry")
    void testAuthenticationFailureMapping() {
        server.createContext("/api/v1/auth-fail", exchange -> {
            requestCounter.incrementAndGet();
            byte[] response = "{\"error\": \"Unauthorized\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        WebClient webClient = WebClient.builder().baseUrl(serverUrl).build();
        BankHttpTransport transport = new BankHttpTransport(webClient, "PARTNER_A", "client-1");

        assertThatThrownBy(() -> transport.execute(HttpMethod.POST, "/api/v1/auth-fail", "{}", "FINX-REL-OP-2"))
                .isInstanceOf(BankAuthenticationException.class)
                .hasMessageContaining("401");

        // Assert exactly 1 attempt made: zero automatic retry
        assertThat(requestCounter.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("HTTP 429 maps to BankRateLimitedException without retry")
    void testRateLimitMapping() {
        server.createContext("/api/v1/rate-limit", exchange -> {
            requestCounter.incrementAndGet();
            exchange.getResponseHeaders().set("Retry-After", "30");
            byte[] response = "{\"error\": \"Too Many Requests\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        WebClient webClient = WebClient.builder().baseUrl(serverUrl).build();
        BankHttpTransport transport = new BankHttpTransport(webClient, "PARTNER_A", "client-1");

        assertThatThrownBy(() -> transport.execute(HttpMethod.POST, "/api/v1/rate-limit", "{}", "FINX-REL-OP-3"))
                .isInstanceOf(BankRateLimitedException.class)
                .hasMessageContaining("Rate limit");

        assertThat(requestCounter.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("HTTP 500/502 maps to BankProtocolException without automatic retry")
    void testServerErrorMapping() {
        server.createContext("/api/v1/server-error", exchange -> {
            requestCounter.incrementAndGet();
            byte[] response = "{\"error\": \"Internal Server Error\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        WebClient webClient = WebClient.builder().baseUrl(serverUrl).build();
        BankHttpTransport transport = new BankHttpTransport(webClient, "PARTNER_A", "client-1");

        assertThatThrownBy(() -> transport.execute(HttpMethod.POST, "/api/v1/server-error", "{}", "FINX-REL-OP-4"))
                .isInstanceOf(BankProtocolException.class)
                .hasMessageContaining("500");

        assertThat(requestCounter.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("HTTP 400/422 maps to BankRejectedRequestException")
    void testBadRequestMapping() {
        server.createContext("/api/v1/bad-request", exchange -> {
            requestCounter.incrementAndGet();
            byte[] response = "{\"error\": \"Invalid Field\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(422, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        WebClient webClient = WebClient.builder().baseUrl(serverUrl).build();
        BankHttpTransport transport = new BankHttpTransport(webClient, "PARTNER_A", "client-1");

        assertThatThrownBy(() -> transport.execute(HttpMethod.POST, "/api/v1/bad-request", "{}", "FINX-REL-OP-5"))
                .isInstanceOf(BankRejectedRequestException.class)
                .hasMessageContaining("422");

        assertThat(requestCounter.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Request signing headers populated when signer configured")
    void testRequestSigningHeadersPopulated() {
        HmacSha256BankRequestSigner signer = new HmacSha256BankRequestSigner("key-1", "secret-key-32-chars-length-1234");

        server.createContext("/api/v1/signed", exchange -> {
            requestCounter.incrementAndGet();
            String sig = exchange.getRequestHeaders().getFirst("X-Signature");
            String algo = exchange.getRequestHeaders().getFirst("X-Signature-Algorithm");
            String keyId = exchange.getRequestHeaders().getFirst("X-Key-Id");

            boolean valid = sig != null && !sig.isBlank() && "HMAC-SHA256".equals(algo) && "key-1".equals(keyId);
            byte[] response = ("{\"valid\": " + valid + "}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        WebClient webClient = WebClient.builder().baseUrl(serverUrl).build();
        BankHttpTransport transport = new BankHttpTransport(webClient, "PARTNER_A", "client-1", signer);

        BankHttpTransport.TransportResponse resp = transport.execute(
                HttpMethod.POST,
                "/api/v1/signed",
                "{\"payload\": 123}",
                "FINX-REL-OP-6"
        );

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("\"valid\": true");
    }
}
