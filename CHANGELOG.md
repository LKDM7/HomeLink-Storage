# Changelog

## 1.4.0 — Storage Pipes

- Pipes also operate standalone without a Controller or HE, with owner permissions,
  shared standalone budgets, persistent cargo and local drops on pipe destruction.

- Add one craftable Storage Pipe to the existing Storage JAR: automatic sided
  connections, transparent graphite/copper model and real travelling cargo.
- Per-face container-relative direction, explicit arming, whitelist/blacklist,
  searchable multi-selection catalogue, revision conflict handling and FR/EN manual.
- Existing Controller coordinates physical graphs, shared budgets and HE payment.
- Persistent dimension ledger, delayed insertion, partial delivery, rerouting,
  pause/recovery and single resolution on Controller destruction.
- Respect HomeCore sided item ports, ownership and permissions; expose aggregate
  telemetry without adding a device for every pipe.
- Add logical, dedicated-server, clean-restart and client validation scenarios.

See docs/PIPES_VALIDATION.md for executed checks and remaining manual coverage.
Dependencies remain HomeCore 1.13.0 and HomeLink Energy 0.5.0; Minecraft 1.21.1,
NeoForge 21.1.251. Licence and author are unchanged.
