package io.cattle.platform.engine.process;

/** Permanent authorization rejection; never contains a payload or credential. */
public final class ProcessAuthorizationDeniedException extends RuntimeException {
    private final String code;

    public ProcessAuthorizationDeniedException(String code) {
        super("Process authorization was denied");
        this.code = code != null && code.matches("[A-Za-z0-9_]{1,128}") ? code : "authorization_denied";
    }

    public String getCode() { return code; }
}
