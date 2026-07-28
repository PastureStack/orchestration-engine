package io.cattle.platform.iaas.api.auth.mfa;

import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Properties;

import jakarta.inject.Inject;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import org.apache.commons.lang3.StringUtils;

public class MfaMailService {

    @Inject
    MfaPolicyService policyService;

    public void send(String recipient, String subject, String body) {
        send(policyService.getSmtpConfiguration(), recipient, subject, body);
    }

    public void send(SmtpConfiguration config, String recipient, String subject, String body) {
        validate(config);
        String address = validateAddress(recipient);

        String protocol = config.isSsl() ? "smtps" : "smtp";
        Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", protocol);
        properties.setProperty("mail.transport.protocol.rfc822", protocol);
        properties.setProperty("mail." + protocol + ".host", config.getHost());
        properties.setProperty("mail." + protocol + ".port", String.valueOf(config.getPort()));
        properties.setProperty("mail." + protocol + ".connectiontimeout",
                String.valueOf(config.getConnectionTimeoutMillis()));
        properties.setProperty("mail." + protocol + ".timeout",
                String.valueOf(config.getReadTimeoutMillis()));
        properties.setProperty("mail." + protocol + ".writetimeout",
                String.valueOf(config.getReadTimeoutMillis()));
        properties.setProperty("mail." + protocol + ".starttls.enable",
                String.valueOf(config.isStartTls()));
        properties.setProperty("mail." + protocol + ".starttls.required",
                String.valueOf(config.isStartTls()));
        properties.setProperty("mail." + protocol + ".ssl.checkserveridentity", "true");
        boolean authenticate = StringUtils.isNotBlank(config.getUsername());
        properties.setProperty("mail." + protocol + ".auth", String.valueOf(authenticate));

        Authenticator authenticator = authenticate ? new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.getUsername(),
                        StringUtils.defaultString(config.getPassword()));
            }
        } : null;

        Session session = Session.getInstance(properties, authenticator);
        session.setDebug(false);
        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(config.getFrom(), true));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(address, true));
            message.setSubject(StringUtils.defaultIfBlank(subject, "PastureStack security notice"),
                    StandardCharsets.UTF_8.name());
            message.setText(StringUtils.defaultString(body), StandardCharsets.UTF_8.name(), "plain");
            message.setSentDate(new Date());
            Transport.send(message);
        } catch (MessagingException e) {
            throw new ClientVisibleException(ResponseCodes.SERVICE_UNAVAILABLE, "SecurityEmailFailed",
                    "The security email could not be delivered. Verify the SMTP configuration.", null);
        }
    }

    public void validate(SmtpConfiguration config) {
        boolean loopback = config != null && ("localhost".equalsIgnoreCase(config.getHost())
                || "127.0.0.1".equals(config.getHost())
                || "::1".equals(config.getHost()));
        boolean credentialsComplete = config != null
                && (StringUtils.isBlank(config.getUsername())
                ? StringUtils.isBlank(config.getPassword())
                : StringUtils.isNotBlank(config.getPassword()));
        if (config == null || !config.isEnabled()
                || StringUtils.isAnyBlank(config.getHost(), config.getFrom())
                || config.getHost().contains("://")
                || config.getPort() < 1 || config.getPort() > 65535
                || (config.isSsl() && config.isStartTls())
                || (!config.isSsl() && !config.isStartTls() && !loopback)
                || !credentialsComplete) {
            throw new ClientVisibleException(ResponseCodes.CONFLICT, "SmtpNotConfigured",
                    "SMTP must have a host, port, sender, complete credentials, and one TLS mode. "
                            + "Plain SMTP is allowed only on the loopback interface for testing.",
                    null);
        }
        validateAddress(config.getFrom());
    }

    public String validateAddress(String value) {
        String address = StringUtils.trimToEmpty(value);
        if (address.length() > 254 || address.contains("\r") || address.contains("\n")) {
            throw new ClientVisibleException(ResponseCodes.BAD_REQUEST, "InvalidEmailAddress",
                    "Enter one valid email address.", null);
        }
        try {
            InternetAddress parsed = new InternetAddress(address, true);
            parsed.validate();
            if (!address.equals(parsed.getAddress())) {
                throw new MessagingException("Display names are not accepted");
            }
            return address;
        } catch (MessagingException e) {
            throw new ClientVisibleException(ResponseCodes.BAD_REQUEST, "InvalidEmailAddress",
                    "Enter one valid email address.", null);
        }
    }
}
