package com.acme.opsweave.platform.identity;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.http.client.*;
import org.springframework.mock.http.client.*;

class OidcHttpTest {
    static final OidcSettings SETTINGS = new OidcSettings("https://id.example", "https://id.example/auth", "https://id.example/token", "https://id.example/jwks", "client", "fixture-secret-not-production", "https://ops.example", "/grants.json", false, 900);
    @Test void tokenAndJwksShareFourSlotsAndClosingTwiceCannotOverRelease() throws Exception {
        var boundary = OidcHttp.boundary(SETTINGS); var responses = new ArrayList<ClientHttpResponse>();
        var token = new MockClientHttpRequest(HttpMethod.POST, URI.create(SETTINGS.tokenUri()));
        var jwks = new MockClientHttpRequest(HttpMethod.GET, URI.create(SETTINGS.jwkSetUri()));
        ClientHttpRequestExecution execution = (request, bytes) -> new MockClientHttpResponse("{}".getBytes(), HttpStatus.OK);
        try {
            for (int i = 0; i < 4; i++) responses.add(boundary.intercept(i % 2 == 0 ? token : jwks, new byte[0], execution));
            assertThrows(IOException.class, () -> boundary.intercept(token, new byte[0], execution));
            responses.getFirst().close(); responses.getFirst().close(); responses.add(boundary.intercept(jwks, new byte[0], execution));
            assertThrows(IOException.class, () -> boundary.intercept(token, new byte[0], execution));
        } finally { responses.forEach(ClientHttpResponse::close); }
    }
    @Test void onlyFixedEndpointsCanBeDispatched() {
        var boundary = OidcHttp.boundary(SETTINGS);
        for (String url : List.of("https://other.example/token", SETTINGS.tokenUri() + "?redirect=x", "http://id.example/token"))
            assertThrows(IOException.class, () -> boundary.intercept(new MockClientHttpRequest(HttpMethod.POST, URI.create(url)), new byte[0], (request, body) -> { throw new AssertionError("Must not dispatch"); }));
    }
    @Test void oversizedResponseWithoutLengthIsBoundedAndFailedDispatchReleasesSlot() throws Exception {
        var boundary = OidcHttp.boundary(SETTINGS); var request = new MockClientHttpRequest(HttpMethod.GET, URI.create(SETTINGS.jwkSetUri()));
        for (int i = 0; i < 8; i++) assertThrows(IOException.class, () -> boundary.intercept(request, new byte[0], (r, b) -> { throw new IOException("Unavailable"); }));
        try (var response = boundary.intercept(request, new byte[0], (r, b) -> new MockClientHttpResponse(new byte[65537], HttpStatus.OK))) {
            assertThrows(IOException.class, () -> response.getBody().readAllBytes());
        }
    }
}
