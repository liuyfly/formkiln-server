package io.formkiln.server.metrics;

import io.formkiln.server.form.AnswerValidator.ValidationError;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Application metrics (Prometheus at {@code /actuator/prometheus}):
 * <ul>
 *   <li>{@code formkiln_submissions_total{form, result}}: accepted, invalid, captcha_failed, honeypot
 *   <li>{@code formkiln_validation_errors_total{form, field, code}}: which fields people get wrong
 *   <li>{@code formkiln_webhook_deliveries_total{result}}: succeeded, failed (will retry), dead
 *   <li>{@code formkiln_mail_total{kind, result}}: sent, failed (will retry), dead
 *   <li>{@code formkiln_logins_total{result}}: succeeded, failed, locked, mfa_failed
 *   <li>{@code formkiln_webhook_pending}, {@code formkiln_mail_pending}: queued, not yet sent
 * </ul>
 * Tags only take bounded values: form keys and field keys come from published forms.
 */
@Component
public class FormkilnMetrics implements MeterBinder {

    private final JdbcClient jdbc;
    private MeterRegistry registry;

    public FormkilnMetrics(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("formkiln.webhook.pending", this, m -> m.count("webhook_delivery"))
                .description("Webhook deliveries waiting to be sent")
                .register(registry);
        Gauge.builder("formkiln.mail.pending", this, m -> m.count("mail_outbox"))
                .description("Emails waiting to be sent")
                .register(registry);
    }

    private double count(String table) {
        try {
            return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE status = 'pending'").query(Long.class).single();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private void increment(String name, String description, String... tags) {
        if (registry != null) {
            Counter.builder(name).description(description).tags(tags).register(registry).increment();
        }
    }

    public void submission(String form, String result) {
        increment("formkiln.submissions", "Submissions received, by result", "form", form, "result", result);
    }

    /** One count per error, tagged with the top-level field of its path. */
    public void validationErrors(String form, List<ValidationError> errors) {
        for (ValidationError error : errors) {
            String path = error.path();
            int dot = path.indexOf('.');
            increment("formkiln.validation.errors", "Validation errors on submission, by field and code", "form", form,
                    "field", dot < 0 ? path : path.substring(0, dot), "code", error.code());
        }
    }

    public void webhook(String result) {
        increment("formkiln.webhook.deliveries", "Webhook delivery attempts, by result", "result", result);
    }

    public void mail(String kind, String result) {
        increment("formkiln.mail", "Email sending attempts, by kind and result", "kind", kind, "result", result);
    }

    /** From the account audit actions: login.succeeded, login.failed, login.locked, mfa.failed. */
    public void accountAction(String action) {
        String result = switch (action) {
            case "login.succeeded" -> "succeeded";
            case "login.failed" -> "failed";
            case "login.locked" -> "locked";
            case "mfa.failed" -> "mfa_failed";
            default -> null;
        };
        if (result != null) {
            increment("formkiln.logins", "Sign-in attempts, by result", "result", result);
        }
    }
}
