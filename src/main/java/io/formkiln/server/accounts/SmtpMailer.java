package io.formkiln.server.accounts;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Sends email through SMTP ({@code spring.mail.*}, configured by FORMKILN_SMTP_*). */
@Component
public class SmtpMailer implements Mailer {

    private final JavaMailSender sender;
    private final String from;

    public SmtpMailer(JavaMailSender sender, @Value("${formkiln.mail.from}") String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override
    public void send(Message message) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(message.to());
        mail.setSubject(message.subject());
        mail.setText(message.body());
        sender.send(mail);
    }
}
