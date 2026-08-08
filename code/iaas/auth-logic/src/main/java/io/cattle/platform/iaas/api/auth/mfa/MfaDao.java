package io.cattle.platform.iaas.api.auth.mfa;

import io.cattle.platform.core.model.Credential;

import java.util.List;
import java.util.Map;

public interface MfaDao {

    Credential create(long accountId, String kind, String publicValue, String secretValue,
                      Map<String, Object> data);

    Credential findActive(String kind, String publicValue);

    List<? extends Credential> listActive(long accountId, String... kinds);

    List<? extends Credential> listActiveByKind(String kind);

    Credential save(Credential credential, String secretValue, Map<String, Object> data);

    void deactivate(Credential credential, String reason);
}
