package com.finx.security.ratelimit;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AuthRateLimitFilterTest {

    @Test
    void blocksRequestsAfterConfiguredAuthenticationLimit() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(true, 1);
        AtomicInteger continued = new AtomicInteger();

        MockHttpServletResponse firstResponse = invoke(filter, authRequest("192.0.2.10"), continued);
        MockHttpServletResponse secondResponse = invoke(filter, authRequest("192.0.2.10"), continued);

        assertThat(firstResponse.getStatus()).isEqualTo(200);
        assertThat(secondResponse.getStatus()).isEqualTo(429);
        assertThat(secondResponse.getContentType()).isEqualTo("application/json");
        assertThat(secondResponse.getContentAsString()).contains("Rate limit exceeded");
        assertThat(continued).hasValue(1);
    }

    @Test
    void doesNotApplyAuthenticationLimitToOtherPaths() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(true, 1);
        AtomicInteger continued = new AtomicInteger();

        invoke(filter, authRequest("192.0.2.11"), continued);
        MockHttpServletRequest healthRequest = new MockHttpServletRequest("GET", "/api/health");
        healthRequest.setRemoteAddr("192.0.2.11");
        MockHttpServletResponse healthResponse = invoke(filter, healthRequest, continued);

        assertThat(healthResponse.getStatus()).isEqualTo(200);
        assertThat(continued).hasValue(2);
    }

    @Test
    void usesFirstForwardedAddressToSeparateClients() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(true, 1);
        AtomicInteger continued = new AtomicInteger();
        MockHttpServletRequest clientAFirst = authRequest("10.0.0.1");
        clientAFirst.addHeader("X-Forwarded-For", "198.51.100.20, 10.0.0.2");
        MockHttpServletRequest clientB = authRequest("10.0.0.1");
        clientB.addHeader("X-Forwarded-For", "198.51.100.21, 10.0.0.2");
        MockHttpServletRequest clientASecond = authRequest("10.0.0.1");
        clientASecond.addHeader("X-Forwarded-For", "198.51.100.20, 10.0.0.3");

        invoke(filter, clientAFirst, continued);
        MockHttpServletResponse clientBResponse = invoke(filter, clientB, continued);
        MockHttpServletResponse clientAResponse = invoke(filter, clientASecond, continued);

        assertThat(clientBResponse.getStatus()).isEqualTo(200);
        assertThat(clientAResponse.getStatus()).isEqualTo(429);
        assertThat(continued).hasValue(2);
    }

    @Test
    void disabledFilterAllowsRepeatedAuthenticationRequests() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(false, 1);
        AtomicInteger continued = new AtomicInteger();

        MockHttpServletResponse first = invoke(filter, authRequest("192.0.2.12"), continued);
        MockHttpServletResponse second = invoke(filter, authRequest("192.0.2.12"), continued);

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(second.getStatus()).isEqualTo(200);
        assertThat(continued).hasValue(2);
    }

    private static MockHttpServletRequest authRequest(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr(remoteAddress);
        return request;
    }

    private static MockHttpServletResponse invoke(AuthRateLimitFilter filter,
                                                  MockHttpServletRequest request,
                                                  AtomicInteger continued) throws IOException, jakarta.servlet.ServletException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (ignoredRequest, ignoredResponse) -> continued.incrementAndGet();
        filter.doFilter(request, response, chain);
        return response;
    }
}