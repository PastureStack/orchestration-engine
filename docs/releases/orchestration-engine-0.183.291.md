# Orchestration Engine v0.183.291

Preserve the service `restartPolicy` all the way to the container launch
configuration. Service creation and upgrade no longer silently discard the
policy selected in the API or Web Console.

The focused regression test proves that both the policy map and generated
instance name survive launch-data preparation. The complete dependent Maven
reactor remains green.
