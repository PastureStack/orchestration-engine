package io.cattle.platform.iaas.api.auth.mfa;

public class SmtpConfiguration {

    private final boolean enabled;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String from;
    private final boolean startTls;
    private final boolean ssl;
    private final int connectionTimeoutMillis;
    private final int readTimeoutMillis;
    private final int codeTtlSeconds;

    public SmtpConfiguration(boolean enabled, String host, int port, String username, String password,
                             String from, boolean startTls, boolean ssl, int connectionTimeoutMillis,
                             int readTimeoutMillis, int codeTtlSeconds) {
        this.enabled = enabled;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.from = from;
        this.startTls = startTls;
        this.ssl = ssl;
        this.connectionTimeoutMillis = connectionTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.codeTtlSeconds = codeTtlSeconds;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getFrom() {
        return from;
    }

    public boolean isStartTls() {
        return startTls;
    }

    public boolean isSsl() {
        return ssl;
    }

    public int getConnectionTimeoutMillis() {
        return connectionTimeoutMillis;
    }

    public int getReadTimeoutMillis() {
        return readTimeoutMillis;
    }

    public int getCodeTtlSeconds() {
        return codeTtlSeconds;
    }
}
