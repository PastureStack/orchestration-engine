package io.cattle.platform.docker.machine.launch;

import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.core.dao.DataDao;
import io.cattle.platform.lock.definition.LockDefinition;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.service.launcher.GenericServiceLauncher;
import io.cattle.platform.ssh.common.SshKeyGen;
import io.cattle.platform.token.impl.RSAKeyProvider;
import io.cattle.platform.token.impl.RSAPrivateKeyHolder;
import io.cattle.platform.util.type.InitializationTask;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

import jakarta.inject.Inject;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class AuthServiceLauncher extends GenericServiceLauncher implements InitializationTask {
    private static final AuthServiceLauncherSettings DEFAULT_SETTINGS = ArchaiusAuthServiceLauncherSettings.create();

    @Inject
    RSAKeyProvider keyProvider;

    @Inject
    ObjectManager objectManager;

    @Inject
    DataDao dataDao;

    private static final Logger log = LoggerFactory.getLogger(AuthServiceLauncher.class);

    public static final ConfigProperty<String> SECURITY_SETTING = DEFAULT_SETTINGS.securityProperty();
    public static final ConfigProperty<String> EXTERNAL_AUTH_PROVIDER_SETTING = DEFAULT_SETTINGS.externalAuthProviderProperty();
    public static final ConfigProperty<String> NO_IDENTITY_LOOKUP_SETTING = DEFAULT_SETTINGS.noIdentityLookupProperty();
    public static final ConfigProperty<String> API_AUTH_SHIBBOLETH_REDIRECT_WHITELIST_SETTING = DEFAULT_SETTINGS.shibbolethRedirectWhitelistProperty();

    private AuthServiceLauncherSettings settings;

    public AuthServiceLauncher() {
        this(DEFAULT_SETTINGS);
    }

    AuthServiceLauncher(AuthServiceLauncherSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public void setSettings(AuthServiceLauncherSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    protected boolean shouldRun() {
        return settings.launchAuthService();
    }

    @Override
    protected String binaryPath() {
        return settings.authServiceExecutable();
    }

    @Override
    protected void setEnvironment(Map<String, String> env) {
        Credential cred = getCredential();
        env.put("CATTLE_ACCESS_KEY", cred.getPublicValue());
        env.put("CATTLE_SECRET_KEY", cred.getSecretValue());
        env.put("CATTLE_URL", LocalCattleApi.url());
        String pubKey = getPublicKey();
        if (pubKey == null) {
            throw new RuntimeException("Couldn't get public key for auth-service.");
        }
        env.put("RSA_PUBLIC_KEY_CONTENTS", pubKey);
        String privateKey = getPrivateKey();
        if (privateKey == null) {
            throw new RuntimeException("Couldn't get private key for auth-service.");
        }
        env.put("RSA_PRIVATE_KEY_CONTENTS", privateKey);
    }

    @Override
    protected void prepareProcess(ProcessBuilder pb) throws IOException {
        String key = dataDao.getOrCreate("auth.config.key", false, new Callable<String>() {
            @Override
            public String call() throws Exception {
                SecureRandom random = new SecureRandom();
                byte[] bytes = new byte[32];
                random.nextBytes(bytes);
                return Hex.encodeHexString(bytes);
            }
        });
        File keyFile = new File("authConfigFile.txt");
        try(FileOutputStream fos = new FileOutputStream(keyFile)) {
            try {
                IOUtils.write(Hex.decodeHex(key.toCharArray()), fos);
            } catch (DecoderException e) {
                throw new IOException(e);
            }
        }

        List<String> args = pb.command();
        args.add("--auth-config-file");
        args.add("authConfigFile.txt");
    }

    @Override
    protected LockDefinition getLock() {
        return null;
    }

    @Override
    protected boolean isReady() {
        return LocalCattleApi.isReady();
    }

    public String getPublicKey() {
        for (Map.Entry<String, PublicKey> entry : keyProvider.getPublicKeys().entrySet()) {
            try {
                return SshKeyGen.writePublicKey(entry.getValue());
            } catch (Exception e) {
                log.error("getPublicKey: Failed to write PEM", e);
            }
        }
        return null;
    }

    public String getPrivateKey() {
        RSAPrivateKeyHolder keyHolder = keyProvider.getPrivateKey();
        if(keyHolder == null) {
            return null;
        }
        try {
            return SshKeyGen.toPEM(keyProvider.getPrivateKey().getKey());
        } catch (Exception e) {
            log.error("getPrivateKey: Failed to write PEM", e);
            return null;
        }
    }

    @Override
    protected List<ConfigProperty<String>> getReloadSettings() {
        List<ConfigProperty<String>> list = new ArrayList<ConfigProperty<String>>();
        list.add(settings.authEnablerProperty());
        list.add(settings.securityProperty());
        list.add(settings.authServiceLogLevelProperty());
        list.add(settings.authServiceConfigUpdateTimestampProperty());
        list.add(settings.shibbolethRedirectWhitelistProperty());
        return list;
    }

    @Override
    public void reload() {
        if (!shouldRun()) {
            return;
        }

        try {
            StringBuilder authUrl = new StringBuilder(settings.authServiceUrl());
            LocalReloadRequest.post(authUrl + "/reload");
        } catch (IOException e) {
            log.info("Failed to reload auth service: {}", e.getMessage());
        }
    }

}
