package io.formkiln.server.accounts;

/** Sends plain-text email. The default implementation uses SMTP ({@link SmtpMailer}). */
public interface Mailer {

    /** One outgoing message. */
    record Message(String to, String subject, String body) {}

    void send(Message message);
}
