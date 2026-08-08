package io.cattle.platform.core.constants;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class CredentialConstants {

    public static final String TYPE = "credential";

    public static final String KIND_API_KEY = "apiKey";
    public static final String KIND_PASSWORD = "password";
    public static final String KIND_AGENT_API_KEY = "agentApiKey";
    public static final String KIND_SSH_KEY = "sshKey";
    /**
     * Internal login-identity link.  This credential kind is created only by
     * the authentication subsystem and must never be accepted from the public
     * generic credential API.
     */
    public static final String KIND_AUTH_IDENTITY = "authIdentity";
    public static final String KIND_AUTH_IDENTITY_PROOF_USE = "authIdentityProofUse";
    public static final String KIND_AUTH_PROVIDER_SWITCH = "authProviderSwitch";
    public static final String KIND_MFA_TOTP = "mfaTotp";
    public static final String KIND_MFA_WEBAUTHN = "mfaWebAuthn";
    public static final String KIND_MFA_RECOVERY_CODE = "mfaRecoveryCode";
    public static final String KIND_MFA_LOGIN_CHALLENGE = "mfaLoginChallenge";
    public static final String KIND_MFA_ENROLLMENT_CHALLENGE = "mfaEnrollmentChallenge";
    public static final String KIND_MFA_RECOVERY_EMAIL = "mfaRecoveryEmail";
    public static final String KIND_MFA_RECOVERY_EMAIL_CODE = "mfaRecoveryEmailCode";
    public static final String KIND_MFA_SYSTEM_CONFIG = "mfaSystemConfig";
    public static final String KIND_MFA_ATTEMPT_STATE = "mfaAttemptState";
    public static final String KIND_MFA_SECURITY_CHALLENGE = "mfaSecurityChallenge";
    public static final String KIND_MFA_SECURITY_TICKET = "mfaSecurityTicket";

    public static final String LINK_PEM_FILE = "pem";
    public static final String LINK_CERTIFICATE = "certificate";

    public static final String KIND_REGISTRY_CREDENTIAL = "registryCredential";
    public static final String PUBLIC_VALUE = "publicValue";
    public static final String SECRET_VALUE = "secretValue";

    public static final String PROCESSS_DEACTIVATE = "credential.deactivate";
    public static final String PROCESSS_REMOVE = "credential.remove";

    public static final Set<String> CREDENTIAL_TYPES_TO_FILTER = Collections.unmodifiableSet(new HashSet<>(Arrays
            .asList(
                    KIND_API_KEY, KIND_PASSWORD
            )));

    public static final Set<String> INTERNAL_CREDENTIAL_TYPES = Collections.unmodifiableSet(new HashSet<>(Arrays
            .asList(
                    KIND_AUTH_IDENTITY,
                    KIND_AUTH_IDENTITY_PROOF_USE,
                    KIND_AUTH_PROVIDER_SWITCH,
                    KIND_MFA_TOTP,
                    KIND_MFA_WEBAUTHN,
                    KIND_MFA_RECOVERY_CODE,
                    KIND_MFA_LOGIN_CHALLENGE,
                    KIND_MFA_ENROLLMENT_CHALLENGE,
                    KIND_MFA_RECOVERY_EMAIL,
                    KIND_MFA_RECOVERY_EMAIL_CODE,
                    KIND_MFA_SYSTEM_CONFIG,
                    KIND_MFA_ATTEMPT_STATE,
                    KIND_MFA_SECURITY_CHALLENGE,
                    KIND_MFA_SECURITY_TICKET
            )));
}
