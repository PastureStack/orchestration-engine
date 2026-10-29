package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import java.util.List;
import org.junit.After;
import org.junit.Test;

/** Only the new reviewed routes, one changed owner action and the full source fingerprint; no old tuple-suite replay. */
public class AiCatalogLaunchOperationContractTest {
    @After public void clear() { ApiContext.remove(); }
    @Test public void completeSourceFingerprintAndUnknownMutationRemainBound() throws Exception {
        new ApiKeyOperationInventoryProjectionTest("source coverage", List.of()).projectedTupleKeepsItsReviewedSemanticAndAdmissionDisposition();
    }
    @Test public void newRoutesRetainCookieOnlyHumanActionsAndOnlyExactServiceLinksBecomeUpdate() throws Exception {
        for (List<String> row : List.of(
                List.of("aiCatalogStackLaunchOptions", "collection", "POST", "", "", "read"),
                List.of("aiCatalogStackLaunchPlan", "collection", "GET", "", "", "read"),
                List.of("aiCatalogStackLaunchPlan", "collection", "POST", "", "", "create"),
                List.of("aiCatalogStackLaunchPlan", "resource", "GET", "", "", "read"),
                List.of("aiCatalogStackLaunchPlan", "resource", "POST", "apply", "", "create"),
                List.of("aiCatalogStackLaunchPlan", "resource", "POST", "confirm", "", "ai-catalog-launch:confirm"),
                List.of("aiCatalogStackLaunchPlan", "resource", "POST", "cancel", "", "ai-catalog-launch:cancel"),
                List.of("aiCatalogStackLaunchPlan", "resource", "POST", "completeInputs", "", "ai-catalog-launch:completeInputs"),
                List.of("service", "resource", "POST", "setservicelinks", "", "update"))) {
            new ApiKeyOperationInventoryProjectionTest(String.join("/", row), row).projectedTupleKeepsItsReviewedSemanticAndAdmissionDisposition();
            ApiContext.remove();
        }
        for (String type : List.of("pluginService", "composeProject", "kubernetesStack", "loadBalancerService"))
            assertFalse(type, ApiKeyOperations.of("POST", "setservicelinks", null, type).registered());
    }
}
