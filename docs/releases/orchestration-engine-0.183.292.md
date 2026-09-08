# Orchestration Engine v0.183.292

Restore `launchConfig.restartPolicy` at the project API authorization boundary.
Service creation and in-service upgrades can now retain the restart policy
selected in the Web Console instead of silently dropping it before deployment.

Regression coverage now checks both authorization-schema visibility and the
downstream container launch configuration. The historical service integration
scenario also verifies the policy on the service and resulting container.
