#!/usr/bin/env python3
"""Check API Key contracts in the assembled WAR, not just the source tree."""
import io
import json
import re
import sys
import zipfile


def require(condition, message):
    if not condition:
        raise SystemExit("ENGINE_API_KEY_ARTIFACT_FAILED " + message)


with zipfile.ZipFile(sys.argv[1]) as war:
    def module(prefix):
        pattern = re.compile(r"WEB-INF/lib/" + re.escape(prefix) + r"-[0-9][^/]*\.jar$")
        entries = [name for name in war.namelist() if pattern.fullmatch(name)]
        require(len(entries) == 1, "module=" + prefix)
        return zipfile.ZipFile(io.BytesIO(war.read(entries[0])))

    with module("cattle-resources") as resources:
        for kind in ("apiKey", "apiKeyRestricted"):
            fields = json.loads(resources.read("schema/base/" + kind + ".json"))["resourceFields"]
            require(fields["apiKeyPolicy"].get("create") and fields["apiKeyPolicy"].get("update"), kind + ".policy")
            require(fields["apiKeyPolicyRevision"].get("update"), kind + ".revision")
            confirmation = fields["securityConfirmation"]
            require(confirmation.get("type") == "password" and confirmation.get("includeInList") is False
                    and confirmation.get("readOnCreateOnly") is True,
                    kind + ".confirmation-privacy")
            for role in ("user", "admin", "project"):
                grants = json.loads(resources.read("schema/" + role + "/" + role + "-auth.json"))["authorize"]
                require(grants.get(kind + ".securityConfirmation") == "cruo", role + ".confirmation-write-metadata")
        preview = json.loads(resources.read("schema/base/apiKeyPolicyPreview.json"))
        require(preview["collectionMethods"] == ["POST"] and preview["resourceMethods"] == [], "preview-methods")
        for field in ("requestDigest", "purpose", "confirmationRequired"):
            require(preview["resourceFields"][field].get("create") is False, "preview-server-owned-" + field)
        audit = json.loads(resources.read("schema/base/auditLog.json"))["resourceFields"]
        audit_fields = ("eventId", "keyId", "decision", "outcome", "httpStatus", "requestId", "actor",
                        "targetType", "targetId", "operation", "policyRevision", "reason", "phase", "preview",
                        "processId", "processName", "hostUuid", "failureCode")
        for field in audit_fields:
            require(field in audit and audit[field].get("create") is False and audit[field].get("update") is False,
                    "audit-server-owned-" + field)
        config = resources.read("cattle-global.properties").decode()
        require("releases/download/v0.13.28/node-agent-0.13.28.tar.gz" in config, "node-lifecycle-producer")
        require("releases/download/v0.38.5/host-api-0.38.5.tar.gz" in config, "host-producer")
        require("releases/download/v0.23.15/websocket-proxy-0.23.15-linux-amd64.tar.xz" in config, "proxy-producer")
        bootstrap = resources.read("config-content/bootstrap/bootstrap.sh").decode()
        launcher = re.search(r"(?ms)^start_agent\(\)\n\{(.*?)^\}\n", bootstrap)
        require(launcher is not None, "node-bootstrap-launcher")
        launch_body = launcher.group(1)
        require("local main=${CATTLE_HOME}/node-agent/apply.sh" in launch_body
                and 'exec "$main" start' in launch_body
                and "export AGENT_PARENT_PID=$PPID" in launch_body,
                "node-bootstrap-formal-lifecycle")
        require("/pyagent/" not in launch_body and "||" not in launch_body, "node-bootstrap-no-legacy-fallback")
    with module("cattle-iaas-auth-logic") as auth:
        for kind in ("ApiKeyAuthorizationService", "ApiKeyPolicyPreviewResourceManager", "ApiKeyDelegationCompletionResourceManager"):
            require("io/cattle/platform/iaas/api/auth/apikey/" + kind + ".class" in auth.namelist(), "class=" + kind)
        delegation = auth.read("io/cattle/platform/iaas/api/auth/apikey/ApiKeyDelegationService.class")
        for marker in (b"handleProxyFailure", b"host-api-backend-v1", b"backendToken", b"BackendAuditCapabilityUnavailable",
                       b"apiKey-stream-handshake-failure-v1|"):
            require(marker in delegation, "handshake-audit=" + marker.decode())
    with module("cattle-host-api") as host:
        producer = host.read("io/cattle/platform/host/api/HostApiProxyTokenManager.class")
        for marker in (b"host-api-backend-v1", b"agentId", b"issuedAt", b"verifiedAgent"):
            require(marker in producer, "backend-proof=" + marker.decode())
    with module("cattle-app-config") as app:
        iaas = app.read("io/cattle/platform/app/IaasApiConfig.class")
        for marker in (b"ApiKeyPolicyTypes", b"ApiKeyDelegationCompletionResourceManager", b"ApiKeyAuthorizationService"):
            require(marker in iaas, "registered=" + marker.decode())
    # v1 keeps the existing frozen role files. The narrowly reviewed factory
    # supplement copies only the new contracts using each frozen role's methods;
    # Server runtime tests verify the effective schemas under both API versions.
    with module("cattle-framework-api") as api:
        factory = api.read("io/cattle/platform/api/schema/FileSchemaFactory.class")
        for marker in (b"mergeApiKeyPolicyFields", b"addApiKeyPolicySchemas", b"apiKeyPolicyRevision", b"apiKeyPolicyPreview",
                       b"mergeAuditLogReadFields") + tuple(name.encode() for name in audit_fields):
            require(marker in factory, "v1-factory=" + marker.decode())

print("ENGINE_API_KEY_ARTIFACT_OK policy=full-closed-custom v1=registered preview=server-owned audit=decision-outcome producers=pinned")
