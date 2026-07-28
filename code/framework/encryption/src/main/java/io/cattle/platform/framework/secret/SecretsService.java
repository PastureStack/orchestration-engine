package io.cattle.platform.framework.secret;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.core.addon.SecretReference;
import io.cattle.platform.core.model.Host;

import java.io.IOException;
import java.util.List;


public interface SecretsService {

    ConfigProperty<String> SECRETS_KEY_NAME = ArchaiusUtil.getStringProperty("secrets.api.local.key.name");

    String encrypt(long accountId, String value) throws IOException;

    String decrypt(long accountId, String value) throws IOException, Exception;

    void delete(long accountId, String value) throws IOException;

    List<SecretValue> getValues(List<SecretReference> refs, Host host) throws IOException;

}
