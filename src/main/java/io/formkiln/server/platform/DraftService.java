package io.formkiln.server.platform;

import io.formkiln.server.accounts.Secrets;
import io.formkiln.server.expression.Values;
import io.formkiln.server.platform.PlatformRepository.Draft;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Anonymous drafts ("save and continue later"). Whoever holds the draft token can read and update
 * the draft; the answers are stored encrypted and expire 30 days after the last save.
 */
@Service
public class DraftService {

    public static final Duration TTL = Duration.ofDays(30);
    public static final int MAX_BYTES = 256 * 1024;

    public record DraftCreated(String token, Instant savedAt, Instant expiresAt) {}

    public record DraftView(Map<String, Object> answers, Instant savedAt, Instant expiresAt) {}

    private final PlatformRepository repository;
    private final Secrets secrets;
    private final JsonMapper json;
    private final Clock clock;

    public DraftService(PlatformRepository repository, Secrets secrets, JsonMapper json, Clock clock) {
        this.repository = repository;
        this.secrets = secrets;
        this.json = json;
        this.clock = clock;
    }

    private byte[] seal(Map<String, Object> answers) {
        byte[] plain = json.writeValueAsBytes(answers == null ? Map.of() : answers);
        if (plain.length > MAX_BYTES) {
            throw new RuntimeException4xx(HttpStatus.CONTENT_TOO_LARGE, "draftTooLarge", "The draft is too large.");
        }
        return secrets.encrypt(plain);
    }

    private Draft find(UUID formId, String token) {
        if (token == null || token.isBlank()) {
            throw new RuntimeException4xx(HttpStatus.NOT_FOUND, "draftNotFound", "The draft does not exist or has expired.");
        }
        return repository.findDraft(formId, Secrets.hash(token), clock.instant())
                .orElseThrow(() -> new RuntimeException4xx(HttpStatus.NOT_FOUND, "draftNotFound", "The draft does not exist or has expired."));
    }

    @Transactional
    public DraftCreated create(UUID formId, Map<String, Object> answers) {
        String token = Secrets.newToken();
        Instant now = clock.instant();
        repository.insertDraft(formId, Secrets.hash(token), seal(answers), now, now.plus(TTL));
        return new DraftCreated(token, now, now.plus(TTL));
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public DraftView read(UUID formId, String token) {
        Draft draft = find(formId, token);
        Map<String, Object> answers = (Map<String, Object>) Values.normalize(json.readValue(secrets.decrypt(draft.data()), Map.class));
        return new DraftView(answers, draft.updatedAt(), draft.expiresAt());
    }

    @Transactional
    public DraftView save(UUID formId, String token, Map<String, Object> answers) {
        Draft draft = find(formId, token);
        Instant now = clock.instant();
        repository.updateDraft(draft.id(), seal(answers), now, now.plus(TTL));
        return new DraftView(answers, now, now.plus(TTL));
    }

    @Transactional
    public void delete(UUID formId, String token) {
        repository.deleteDraft(find(formId, token).id());
    }

    /** Deletes the draft if the token is valid; used after a successful submission. */
    @Transactional
    public void discardQuietly(UUID formId, String token) {
        if (token != null && !token.isBlank()) {
            repository.findDraft(formId, Secrets.hash(token), clock.instant()).ifPresent(d -> repository.deleteDraft(d.id()));
        }
    }

    /** Removes expired drafts once an hour. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void removeExpired() {
        repository.deleteExpiredDrafts(clock.instant());
    }
}
