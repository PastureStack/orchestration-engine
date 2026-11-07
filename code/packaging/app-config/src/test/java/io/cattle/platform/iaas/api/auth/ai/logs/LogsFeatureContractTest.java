package io.cattle.platform.iaas.api.auth.ai.logs;
import static org.junit.Assert.*;
import io.cattle.platform.iaas.api.auth.integration.internal.rancher.TokenAuthLookup;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import org.junit.Test;
public class LogsFeatureContractTest {
    @Test public void genuineSessionOwnerExportsCaptureInterface() throws Exception {
        assertNotNull(TokenAuthLookup.class.getMethod("captureVerifiedSession",ApiRequest.class,long.class));
    }
    @Test public void fixedBoundedLogsOwnerExists() throws Exception {
        assertNotNull(Class.forName("io.cattle.platform.iaas.api.auth.ai.logs.AiContainerLogsService").getMethod("read",ApiRequest.class,java.util.Map.class));
    }
}
