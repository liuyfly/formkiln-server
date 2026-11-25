package io.formkiln.server.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.formkiln.server.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** An unhandled error must reach the client as a 500, not be turned into a 401 by the security chain. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "formkiln.service-token=test-token")
@Import({TestcontainersConfiguration.class, ErrorDispatchTest.Failing.class})
class ErrorDispatchTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Failing {
        @Bean
        FailingController failingController() {
            return new FailingController();
        }
    }

    @RestController
    static class FailingController {
        @GetMapping("/v1/manage/test-failure")
        String fail() {
            throw new IllegalStateException("boom");
        }
    }

    @LocalServerPort
    private int port;

    @Test
    void unhandledErrorsAreServerErrors() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/manage/test-failure"))
                        .header("Authorization", "Bearer test-token")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).doesNotContain("boom").doesNotContain("IllegalStateException");

        // Asking for the error page directly is still denied.
        HttpResponse<String> direct = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/error")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(direct.statusCode()).isIn(401, 403);
    }
}
