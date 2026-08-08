package io.cattle.platform.iaas.api.auth.mfa;

import static org.junit.Assert.assertTrue;

import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class MfaMailServiceTest {

    @Test
    public void deliversUtf8SecurityMessageThroughFakeSmtpServer() throws Exception {
        try (FakeSmtpServer server = new FakeSmtpServer()) {
            MfaMailService service = service(new SmtpConfiguration(true, "127.0.0.1",
                    server.getPort(), null, null, "security@example.test",
                    false, false, 5000, 5000, 600));

            service.send("user@example.test", "Security code",
                    "Your security code is 123456.");

            String message = server.message().get(5, TimeUnit.SECONDS);
            assertTrue(message.contains("Subject: Security code"));
            assertTrue(message.contains("user@example.test"));
            assertTrue(message.contains("123456"));
        }
    }

    @Test(expected = ClientVisibleException.class)
    public void rejectsConflictingTlsModes() {
        MfaMailService service = service(new SmtpConfiguration(true, "smtp.example.test",
                465, null, null, "security@example.test",
                true, true, 5000, 5000, 600));
        service.validate(service.policyService.getSmtpConfiguration());
    }

    @Test(expected = ClientVisibleException.class)
    public void rejectsPlaintextRemoteSmtp() {
        MfaMailService service = service(new SmtpConfiguration(true, "smtp.example.test",
                25, null, null, "security@example.test",
                false, false, 5000, 5000, 600));
        service.validate(service.policyService.getSmtpConfiguration());
    }

    @Test(expected = ClientVisibleException.class)
    public void rejectsIncompleteSmtpCredentials() {
        MfaMailService service = service(new SmtpConfiguration(true, "smtp.example.test",
                587, "security-user", null, "security@example.test",
                true, false, 5000, 5000, 600));
        service.validate(service.policyService.getSmtpConfiguration());
    }

    private MfaMailService service(final SmtpConfiguration configuration) {
        MfaMailService service = new MfaMailService();
        service.policyService = new MfaPolicyService() {
            @Override
            public SmtpConfiguration getSmtpConfiguration() {
                return configuration;
            }
        };
        return service;
    }

    private static class FakeSmtpServer implements AutoCloseable {

        private final ServerSocket server;
        private final CompletableFuture<String> message = new CompletableFuture<>();
        private final Thread thread;

        FakeSmtpServer() throws Exception {
            server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
            thread = new Thread(this::serve, "fake-smtp");
            thread.setDaemon(true);
            thread.start();
        }

        int getPort() {
            return server.getLocalPort();
        }

        CompletableFuture<String> message() {
            return message;
        }

        private void serve() {
            try (Socket socket = server.accept();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.US_ASCII));
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                         socket.getOutputStream(), StandardCharsets.US_ASCII))) {
                reply(writer, "220 fake-smtp ESMTP ready");
                StringBuilder content = new StringBuilder();
                boolean data = false;
                String line;
                while ((line = reader.readLine()) != null) {
                    if (data) {
                        if (".".equals(line)) {
                            data = false;
                            reply(writer, "250 accepted");
                            message.complete(content.toString());
                        } else {
                            content.append(line).append('\n');
                        }
                    } else if (line.startsWith("EHLO") || line.startsWith("HELO")) {
                        reply(writer, "250-fake-smtp");
                        reply(writer, "250 8BITMIME");
                    } else if (line.startsWith("MAIL FROM") || line.startsWith("RCPT TO")) {
                        reply(writer, "250 ok");
                    } else if ("DATA".equals(line)) {
                        data = true;
                        reply(writer, "354 end with .");
                    } else if ("QUIT".equals(line)) {
                        reply(writer, "221 bye");
                        return;
                    } else {
                        reply(writer, "250 ok");
                    }
                }
            } catch (Exception e) {
                message.completeExceptionally(e);
            }
        }

        private void reply(BufferedWriter writer, String value) throws Exception {
            writer.write(value);
            writer.write("\r\n");
            writer.flush();
        }

        @Override
        public void close() throws Exception {
            server.close();
            thread.join(1000);
        }
    }
}
