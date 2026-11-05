# Engine 0.183.374 candidate — not published

Existing ordinary Services can finish an upgrade, roll back, or cancel an upgrade through the existing typed resource plan. Each action uses empty parameters, fresh native role/schema/Key upgrade checks, explicit human approval, private phase/configuration CAS and one normal native action.

Completion requires the matching native process and configuration/readiness readback. Finish and rollback also wait for exact old-generation instances and attachments to retire; rollback may reuse an original instance only under its unchanged native map and UUID. Cancellation verifies only the canceled-upgrade checkpoint and does not complete an upgrade or rollback. cancel_rollback remains unsupported: this Engine has no corresponding native capability.

No generic HTTP transport, raw configuration input, new child permission or native role/action grant is introduced. The 208 prior required methods remain registered, with 11 new focused methods (219 total). Normal CI, original artifact qualification, matching Web/Bridge and Server integration, native business acceptance and weak-model/layout acceptance remain separate. This source change publishes no component and claims no whole-group or whole-243 completion. Existing package security findings and policy remain unchanged.
