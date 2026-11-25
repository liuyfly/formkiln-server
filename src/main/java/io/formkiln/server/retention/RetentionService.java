package io.formkiln.server.retention;

import io.formkiln.server.accounts.AccountRepository;
import io.formkiln.server.accounts.Secrets;
import io.formkiln.server.forms.FormService.NotFoundException;
import io.formkiln.server.platform.PlatformRepository;
import io.formkiln.server.platform.PlatformRepository.FormRow;
import io.formkiln.server.platform.RuntimeException4xx;
import io.formkiln.server.retention.RetentionRepository.FormRetention;
import io.formkiln.server.retention.RetentionRepository.Match;
import io.formkiln.server.storage.ObjectStorage;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deleting submissions: one at a time, automatically after a form's retention period, and every
 * submission of a person (erasure requests). Deleting a submission also deletes its files,
 * webhook deliveries and queued emails (both hold its data).
 */
@Service
public class RetentionService {

    public static final int MAX_DAYS = 36_500;
    static final int BATCH = 500;
    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    /** Matches of an erasure request per form; {@code deleted} is false for a dry run. */
    public record ErasureResult(Map<String, Integer> forms, int total, boolean deleted, String note) {}

    /** Who did it, for the audit log. */
    public record Actor(String type, UUID id, String ip) {}

    private final RetentionRepository repository;
    private final PlatformRepository platform;
    private final ObjectStorage storage;
    private final AccountRepository audit;
    private final JsonMapper json;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final Secrets secrets;

    public RetentionService(RetentionRepository repository, PlatformRepository platform, ObjectStorage storage, AccountRepository audit,
            JsonMapper json, Clock clock, TransactionTemplate transactions, Secrets secrets) {
        this.repository = repository;
        this.platform = platform;
        this.storage = storage;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.transactions = transactions;
        this.secrets = secrets;
    }

    private FormRow form(String key) {
        return platform.findForm(key).orElseThrow(() -> new NotFoundException("No form " + key));
    }

    private void record(Actor actor, String action, String target, Map<String, Object> details) {
        audit.audit(actor.type(), actor.id(), action, target, actor.ip(), details == null ? null : json.writeValueAsString(details));
    }

    /** Deletes rows now and the stored file objects after the transaction commits. */
    private int deleteSubmissions(List<UUID> ids) {
        List<String> keys = repository.delete(ids);
        if (!keys.isEmpty()) {
            Runnable removeObjects = () -> {
                for (String key : keys) {
                    try {
                        storage.delete(key);
                    } catch (Exception e) {
                        log.warn("Could not delete stored file {}: {}", key, e.getMessage());
                    }
                }
            };
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        removeObjects.run();
                    }
                });
            } else {
                removeObjects.run();
            }
        }
        return ids.size();
    }

    // ---------------------------------------------------------------- single submissions

    @Transactional
    public void deleteSubmission(String formKey, UUID submissionId, Actor actor) {
        FormRow form = form(formKey);
        if (!repository.belongsTo(submissionId, form.id())) {
            throw new NotFoundException("No such submission");
        }
        deleteSubmissions(List.of(submissionId));
        record(actor, "submission.deleted", formKey + "/" + submissionId, null);
    }

    // ---------------------------------------------------------------- retention

    @Transactional(readOnly = true)
    public Integer retention(String formKey) {
        return repository.retentionOf(form(formKey).id());
    }

    @Transactional
    public Integer setRetention(String formKey, Integer days, Actor actor) {
        if (days != null && (days < 1 || days > MAX_DAYS)) {
            throw new RuntimeException4xx(HttpStatus.BAD_REQUEST, "invalidRetention", "Keep submissions for 1 to " + MAX_DAYS + " days.");
        }
        FormRow form = form(formKey);
        repository.setRetention(form.id(), days);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("days", days);
        record(actor, "retention.updated", formKey, details);
        return days;
    }

    /** Deletes submissions past their form's retention period. Hourly; safe on several instances. */
    @Scheduled(fixedDelayString = "${formkiln.retention.interval:PT1H}", initialDelayString = "PT2M")
    public void purgeExpired() {
        Instant now = clock.instant();
        for (FormRetention form : repository.formsWithRetention()) {
            Instant cutoff = now.minus(Duration.ofDays(form.days()));
            int purged = 0;
            while (true) {
                Integer deleted = transactions.execute(status -> deleteSubmissions(repository.expired(form.formId(), cutoff, BATCH)));
                purged += deleted == null ? 0 : deleted;
                if (deleted == null || deleted < BATCH) {
                    break;
                }
            }
            if (purged > 0) {
                record(new Actor("system", null, null), "retention.purged", form.key(), Map.of("deleted", purged, "days", form.days()));
                log.info("Deleted {} submissions of {} older than {} days", purged, form.key(), form.days());
            }
        }
    }

    // ---------------------------------------------------------------- erasure requests

    /**
     * A keyed fingerprint of an identifier for the audit log: the same person gives the same value,
     * but it cannot be reversed by hashing guessed addresses without the encryption key.
     */
    String fingerprint(String value) {
        return HexFormat.of().formatHex(secrets.mac("erasure-fingerprint", value.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
    }

    /**
     * Finds (dry run) or deletes every submission of a person: by the subject of embed tokens, or
     * by an email address anywhere in the answers. The audit log keeps a fingerprint of the
     * identifier, not the identifier.
     */
    @Transactional
    public ErasureResult erase(String email, String subject, boolean dryRun, Actor actor) {
        String address = email == null || email.isBlank() ? null : email.strip().toLowerCase(Locale.ROOT);
        String who = subject == null || subject.isBlank() ? null : subject.strip();
        if (address == null && who == null) {
            throw new RuntimeException4xx(HttpStatus.BAD_REQUEST, "identifierRequired", "Give an email address or a subject.");
        }
        if (address != null && (address.length() > 254 || !address.contains("@"))) {
            throw new RuntimeException4xx(HttpStatus.BAD_REQUEST, "invalidEmail", "Not an email address.");
        }
        List<Match> matches = new ArrayList<>();
        if (address != null) {
            matches.addAll(repository.byEmail(address));
        }
        if (who != null) {
            matches.addAll(repository.bySubject(who));
        }
        Map<String, Integer> forms = new LinkedHashMap<>();
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (Match match : matches) {
            if (ids.add(match.id())) {
                forms.merge(match.formKey(), 1, Integer::sum);
            }
        }
        if (!dryRun && !ids.isEmpty()) {
            List<UUID> all = List.copyOf(ids);
            for (int i = 0; i < all.size(); i += BATCH) {
                deleteSubmissions(all.subList(i, Math.min(all.size(), i + BATCH)));
            }
        }
        if (!dryRun) {
            Map<String, Object> details = new LinkedHashMap<>();
            if (address != null) {
                details.put("email", fingerprint(address));
            }
            if (who != null) {
                details.put("subject", fingerprint(who));
            }
            details.put("deleted", ids.size());
            details.put("forms", forms);
            record(actor, "erasure.performed", null, details);
        }
        return new ErasureResult(forms, ids.size(), !dryRun,
                "Sensitive answers are encrypted and are not searched; drafts expire on their own after 30 days.");
    }
}
