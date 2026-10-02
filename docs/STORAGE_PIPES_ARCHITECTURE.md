# Storage Pipes architecture and conservation

## Ownership

Updated requirement: Controllers are optional. `PipeCoordinator` supplies either
the existing Controller context or a standalone owner context, with zero HE cost.
Standalone budgets/queue admission use a stable dimension+owner UUID; this is not a
fake block or inventory. Cargo persists `AutonomousOwner`; old cargo without this
field retains its Controller semantics. Both standalone endpoints must be owned
and explicitly armed by that player. HomeCore machine ownership is checked again.
Adding a Controller does not adopt existing standalone cargo or change its authority.
Breaking a standalone cargo's current segment releases it once locally; unloaded
cargo waits in the ledger. Unknown mutation evidence is never dropped automatically.

`logistics/transit/TransitLedger` is one dimension-scoped SavedData named
`homelink_storage_transit`, format 2 (reads existing format 1). It is the sole owner of extracted ItemStacks.
Pipes persist only owner and per-direction configuration. Client models are copies
for display, never inventory. Dimension comes from the containing SavedData store.
Packets store UUID, authority UUID and position, optional standalone owner, departure HomeNetwork, source and
destination identities/addresses, exact stack, route, progress, state and reason.
Routes are validated after reload against the reconstructed loaded graph.

For conforming handlers the invariant, per item and exact data components, is:

```text
sources + destinations + ledger(active/blocked/recovery) + final drops/player recovery
= initial items, excluding legitimate external changes
```

Departure simulates extraction and insertion before touching the source. Admission
checks the shared Controller queue, route, live permissions, filters and HE.
The manager serializes operations with a reentrancy guard. It provisions HE before
extraction and refunds an empty/cancelled departure. Only the real extraction result
becomes cargo. It never fabricates a stack from the simulation.

Arrival inserts the held stack and subtracts only the returned remainder. A partial
insertion leaves the rest in the ledger and metrics count only accepted items.
Our outstanding cargo reduces simulated capacity; this is **not** a reservation in
a third-party container. Concurrent hoppers/players may fill it before arrival.

Invalid results and exceptions quarantine an endpoint. An uncertain insertion
retains evidence with `Uncertain=true`, disallows subsequent delivery, retrieval
and drops, and logs the problem. An operator must inspect the third-party handler
and saved record; blindly compensating might duplicate items. No universal atomic
transaction is claimed for handlers that mutate and then throw.

Unreadable item records, unknown future ledger versions and duplicate packet IDs
are retained verbatim in `Unreadable`, reported in logs and the recovery view.
Unsupported states and malformed route/direction data are never silently dispatched.
A clean shutdown saves both chunks and ledger, but they are not one atomic disk
transaction. Abrupt process/disk crashes require separate proof and recovery policy.

## Topology and access

`PipeNetworkManager` is dimension-scoped and handles block/load/capability changes.
The graph contains only adjacent pipes. Controllers and inventories are endpoints,
never graph segments. An unloaded neighbour makes a component conservatively
incomplete: another Controller could be hidden there. Such a component does not
elect a replacement manager. Multiple touching Controllers suspend dispatch.
Each reconstruction has a new revision; cargo remains owned by its original UUID.
Changing the Controller's HomeNetwork cannot adopt cargo from its former network.

`PipeEndpoint` addresses the exact neighbouring face. HomeCore ItemApi.BLOCK takes
precedence, including its INPUT/OUTPUT/BOTH restrictions. No null-side fallback.
Capability caches listen for invalidation; `isLoaded` guards all inventory lookups.
The repeating graph/transit reads use `LoadedBlocks` and `getChunkNow`, avoiding
implicit renewal of vanilla UNKNOWN tickets through `Level.getChunk`. The unload
GameTest exposed this distinction and now verifies actual chunk disappearance.
StorageInventoryAdapter's canonical double-chest identity is reused without
`resolveAny`. Pipe endpoints and cargo are excluded from Storage index discovery.
Face keys use a position/direction record, avoiding overflow/collisions from packing
three additional direction bits into an already full 64-bit BlockPos.

HomeCore target devices require matching network or owner. Configuration captures
the authenticated configuring player and automation context. CONFIGURE is rechecked
before live inventory operations; network and target rights are not trusted from
an earlier GUI snapshot. VIEW gates configuration inspection and nearby visuals;
CONTROL gates pause/recovery. Generic containers expose no universal owner/claim
API. Position-identical third-party virtual inventory aliases cannot always be known.

## Work and networking

Topology rebuilds consume a dimension-wide per-tick node budget. Breadth-first route
trees share one traversal among destinations from the same start and keep at most
16 cached origins per component. A Controller admits one departure per interval by
default across all components, with rotating sources/destinations. Route attempts
are separately capped at 8 per Controller tick. Source slots and bounded rotating
destination windows share the slot budget; large inventories are visited in slices.
Limits cap nodes, endpoints, path length, filters and queue admission separately.
Lowering admission limits never deletes existing cargo; lowering filter limits
disarms over-limit faces while preserving their IDs.

`PipeSync` sends leased pictures only to nearby VIEW-authorized players following
the current chunk. Each picture exposes at most eight consecutive tracked segments.
Start/correction/snapshot traffic is capped at 64 messages per dimension tick;
snapshots at chunk entry are capped at 32. Periodic corrections cover walking into
range, pause, long routes and permission changes. Clients expire unsupervised
pictures, including paused pictures, after 60 ticks. Ends clear prior recipients.
Client settings cap render distance and item representations without affecting
server throughput. Large display components may be reduced to an item/glint picture;
the ledger always retains the complete stack.

All metrics belong to the existing StorageDevice, with stable definitions.
`pipe_topology_partial` marks incomplete observations; topology counts are -1 when
unknown. Delivered/minute counts accepted items in a 60-second server-tick window.
Events are emitted on transitions. Pipes add no HomeCore device per segment.

## Native model assets

`scripts/generate_pipe_models.py` generates the native JSON models and three
32×32 RGBA material textures, with Python's standard library. It is an asset
maintenance tool, not a build or runtime dependency. NeoForge's built-in
`neoforge:composite` loader combines solid metal and translucent glass child
models, with two ordered item render passes. Metal UV dimensions follow the
geometry to avoid stretched material details. Glass walls have a small nonzero
thickness so their front and back faces are not coplanar. The glass remains translucent;
graphite and copper are opaque rim bars around an open bore. Straight connections
use a continuous octagonal prism instead of the junction core. A multipart condition
selects the three axes without adding saved blockstate properties. Elbows, T junctions
and crossings keep their independent arms and glass inspection chamber.

Pipe-to-pipe collars and container sockets are separate multipart conditions;
only one is applied to each connected face. A container socket has an open square
mounting plate, three successive octagonal sleeve bands and four raised copper
bolts. Its rear extends 1.025 model pixels into the neighbouring block to reach
recessed vanilla chest walls. Selection follows the flange and sleeve on all six
directions. Junction brackets form continuous edges instead of loose corner cubes.

The six four-valued side properties have 4096 combinations. Collision shapes are
computed lazily with `dynamicShape()` and cached by their physical connection masks
(at most 729 distinct geometries), sharing container/Controller outlines. Bevel boxes
are combined before one final optimization; no per-frame shape generation is needed.
Straight status strips sit on the collar pads; other strips remain on closed chamber
faces. Cargo movement and server routing do not depend on the visual model.


## References checked

- Local resolved HomeCore 1.13.0: ItemApi, ItemPort, ItemPortType, EnergyBuffer,
  NetworkMember, PermissionValidator, DashboardDevice, DeviceMetric.
- Local NeoForge 21.1.251 sources: CompositeModel loader, chunk render-layer union
  and ordered item passes, checked in the resolved sources archive.
- [NeoForge 1.21–1.21.1 capabilities](https://docs.neoforged.net/docs/1.21.1/inventories/capabilities/):
  sided block capabilities, cache lifecycle and invalidation.

No internal Farm, Quarry, Energy, Dashboard or Tasks classes are imported or embedded.
