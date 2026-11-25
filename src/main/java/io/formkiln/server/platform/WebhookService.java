package io.formkiln.server.platform;

import io.formkiln.server.accounts.Secrets;
import io.formkiln.server.forms.FormService.NotFoundException;
import io.formkiln.server.platform.PlatformRepository.Delivery;
import io.formkiln.server.platform.PlatformRepository.Endpoint;
import io.formkiln.server.platform.PlatformRepository.FormRow;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Webhooks through an outbox: a delivery row is written in the submission's transaction and sent
 * later by {@link #deliverDue()}. Requests are signed with HMAC-SHA256
 * ({@code Formkiln-Signature: t=<unix seconds>,v1=<hex HMAC of "t.body">}) and retried with
 * exponential backoff (30 s doubling, 8 attempts, about two hours) before becoming dead.
 */
@Service
public class WebhookService {

    public static final String EVENT_SUBMISSION_CREATED = "submission.created";
    static final int MAX_ATTEMPTS = 8;
    static final Duration FIRST_RETRY = Duration.ofSeconds(30);
    static final Duration LEASE = Duration.ofMinutes(2);
    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    /** A webhook endpoint; {@code secret} is only present right after creation. */
    public record EndpointView(String id, String url, Instant createdAt, String secret) {}

    public record DeliveryView(String id, String event, String submissionId, String status, int attempts, Instant nextAttemptAt,
            Integer lastStatus, String lastError, Instant createdAt, Instant deliveredAt) {

        static DeliveryView of(Delivery d) {
            return new DeliveryView(d.id().toString(), d.event(), d.submissionId().toString(), d.status(), d.attempts(),
                    "pending".equals(d.status()) ? d.nextAttemptAt() : null, d.lastStatus(), d.lastError(), d.createdAt(),
                    d.deliveredAt());
        }
    }

    public static class InvalidWebhookException extends RuntimeException {
        public InvalidWebhookException(String message) {
            super(message);
        }
    }

    private final PlatformRepository repository;
    private final Secrets secrets;
    private final UrlGuard guard;
    private final JsonMapper json;
    private final Clock clock;
    private final io.formkiln.server.metrics.FormkilnMetrics metrics;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public WebhookService(PlatformRepository repository, Secrets secrets, UrlGuard guard, JsonMapper json, Clock clock,
            io.formkiln.server.metrics.FormkilnMetrics metrics) {
        this.repository = repository;
        this.secrets = secrets;
        this.guard = guard;
        this.json = json;
        this.clock = clock;
        this.metrics = metrics;
    }

    private FormRow form(String key) {
        return repository.findForm(key).orElseThrow(() -> new NotFoundException("Form " + key + " is not published"));
    }

    // ---------------------------------------------------------------- management

    @Transactional
    public EndpointView create(String formKey, String url) {
        FormRow form = form(formKey);
        String problem = url == null ? "The URL is required." : guard.checkSyntax(url);
        if (problem != null) {
            throw new InvalidWebhookException(problem);
        }
        String secret = "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(Secrets.randomBytes(32));
        UUID id = repository.insertEndpoint(form.id(), url, secrets.encrypt(secret.getBytes(StandardCharsets.UTF_8)));
        Endpoint endpoint = repository.findEndpoint(id, form.id()).orElseThrow();
        return new EndpointView(id.toString(), endpoint.url(), endpoint.createdAt(), secret);
    }

    @Transactional(readOnly = true)
    public List<EndpointView> list(String formKey) {
        return repository.listEndpoints(form(formKey).id()).stream()
                .map(e -> new EndpointView(e.id().toString(), e.url(), e.createdAt(), null))
                .toList();
    }

    @Transactional
    public void delete(String formKey, UUID id) {
        FormRow form = form(formKey);
        repository.findEndpoint(id, form.id()).orElseThrow(() -> new NotFoundException("No such webhook"));
        repository.deleteEndpoint(id, clock.instant());
    }

    @Transactional(readOnly = true)
    public List<DeliveryView> deliveries(String formKey, UUID endpointId, int limit) {
        FormRow form = form(formKey);
        repository.findEndpoint(endpointId, form.id()).orElseThrow(() -> new NotFoundException("No such webhook"));
        return repository.listDeliveries(endpointId, limit).stream().map(DeliveryView::of).toList();
    }

    @Transactional
    public DeliveryView redeliver(String formKey, UUID deliveryId) {
        FormRow form = form(formKey);
        Delivery delivery = repository.findDelivery(deliveryId).orElseThrow(() -> new NotFoundException("No such delivery"));
        repository.findEndpoint(delivery.endpointId(), form.id()).orElseThrow(() -> new NotFoundException("No such delivery"));
        repository.requeueDelivery(deliveryId, clock.instant());
        return DeliveryView.of(repository.findDelivery(deliveryId).orElseThrow());
    }

    // ---------------------------------------------------------------- outbox

    /** Queues one delivery per endpoint. Runs inside the submission's transaction. */
    public void enqueue(UUID formId, UUID submissionId, Map<String, Object> payload) {
        List<Endpoint> endpoints = repository.listEndpoints(formId);
        if (endpoints.isEmpty()) {
            return;
        }
        byte[] encrypted = secrets.encrypt(json.writeValueAsBytes(payload));
        Instant now = clock.instant();
        for (Endpoint endpoint : endpoints) {
            repository.insertDelivery(endpoint.id(), submissionId, EVENT_SUBMISSION_CREATED, encrypted, now);
        }
    }

    // ---------------------------------------------------------------- delivery

    static String signature(String secret, long timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Sends every due delivery once. Called on a schedule; safe to run on several instances. */
    @Scheduled(fixedDelayString = "${formkiln.webhooks.poll-interval:5000}", initialDelayString = "${formkiln.webhooks.poll-interval:5000}")
    public void deliverDue() {
        Instant now = clock.instant();
        List<Delivery> due = repository.claimDue(now, now.plus(LEASE), 20);
        for (Delivery delivery : due) {
            deliver(delivery);
        }
    }

    private void deliver(Delivery delivery) {
        Endpoint endpoint = repository.findEndpointById(delivery.endpointId()).orElse(null);
        Instant now = clock.instant();
        int attempts = delivery.attempts() + 1;
        Integer status = null;
        String error = null;
        if (endpoint == null) {
            repository.recordDeliveryResult(delivery.id(), "dead", attempts, now, null, "Endpoint deleted", null);
            metrics.webhook("dead");
            return;
        }
        try {
            URI uri = URI.create(endpoint.url());
            error = guard.checkDestination(uri);
            if (error == null) {
                byte[] body = secrets.decrypt(delivery.payload());
                String secret = new String(secrets.decrypt(endpoint.secret()), StandardCharsets.UTF_8);
                HttpResponse<Void> response = http.send(
                        HttpRequest.newBuilder(uri)
                                .timeout(Duration.ofSeconds(10))
                                .header("Content-Type", "application/json")
                                .header("User-Agent", "Formkiln-Webhooks/1")
                                .header("Formkiln-Event", delivery.event())
                                .header("Formkiln-Delivery", delivery.id().toString())
                                .header("Formkiln-Signature", signature(secret, now.getEpochSecond(), body))
                                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                                .build(),
                        HttpResponse.BodyHandlers.discarding());
                status = response.statusCode();
                if (status >= 200 && status < 300) {
                    repository.recordDeliveryResult(delivery.id(), "succeeded", attempts, now, status, null, now);
                    metrics.webhook("succeeded");
                    return;
                }
                error = "HTTP " + status;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "Interrupted";
        } catch (Exception e) {
            error = e.getClass().getSimpleName();
        }
        boolean dead = attempts >= MAX_ATTEMPTS;
        Instant next = now.plus(FIRST_RETRY.multipliedBy(1L << Math.min(attempts - 1, 20)));
        repository.recordDeliveryResult(delivery.id(), dead ? "dead" : "pending", attempts, next, status, truncate(error), null);
        metrics.webhook(dead ? "dead" : "failed");
        log.info("Webhook delivery {} attempt {} failed: {}", delivery.id(), attempts, error);
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 512 ? value : value.substring(0, 512);
    }
}
