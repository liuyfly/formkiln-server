package io.formkiln.server.web;

import io.formkiln.server.accounts.AccountService.AdminPrincipal;
import io.formkiln.server.notifications.NotificationRepository.Settings;
import io.formkiln.server.notifications.NotificationService;
import io.formkiln.server.notifications.NotificationService.MailView;
import io.formkiln.server.notifications.NotificationService.SettingsView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Email notification settings and the mail log of a form. Viewers read; editors change settings. */
@RestController
public class NotificationsApi {

    private static final int MAX_PAGE = 200;

    /** Settings as sent by a client; missing switches are off. */
    public record SettingsRequest(List<String> teamRecipients, Boolean teamIncludeAnswers, Boolean confirmationEnabled,
            String confirmationEmailField, String confirmationSubject, String confirmationBody, Boolean resumeEnabled) {

        Settings toSettings() {
            return new Settings(teamRecipients == null ? List.of() : teamRecipients, Boolean.TRUE.equals(teamIncludeAnswers),
                    Boolean.TRUE.equals(confirmationEnabled), confirmationEmailField, confirmationSubject, confirmationBody,
                    Boolean.TRUE.equals(resumeEnabled));
        }
    }

    private final NotificationService notifications;

    public NotificationsApi(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/v1/manage/forms/{key}/notifications")
    public SettingsView settings(@PathVariable String key) {
        return notifications.settings(key);
    }

    @PutMapping("/v1/manage/forms/{key}/notifications")
    public SettingsView update(@PathVariable String key, @RequestBody SettingsRequest body, HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean member = auth != null && auth.getPrincipal() instanceof AdminPrincipal;
        UUID actorId = member ? ((AdminPrincipal) auth.getPrincipal()).userId() : null;
        return notifications.update(key, body.toSettings(), member ? "user" : "service", actorId, request.getRemoteAddr());
    }

    /** Recent emails of the form (recipients masked), newest first. */
    @GetMapping("/v1/manage/forms/{key}/mail")
    public List<MailView> mail(@PathVariable String key, @RequestParam(defaultValue = "50") int limit) {
        return notifications.log(key, Math.clamp(limit, 1, MAX_PAGE));
    }
}
