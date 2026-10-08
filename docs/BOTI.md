# BOTI — "Bigger On The Inside"

How Artron Industries shows a TARDIS interior through its exterior door (and the outside world through the interior
door), and lets players and entities walk through, on Minecraft 26.3 / NeoForge 26.3.0.57-beta.

## The idea in one paragraph

The client only ever holds one `ClientLevel`, so the far side of a door cannot be rendered from a level. Instead, the
server captures a **snapshot** of the blocks behind the far door and streams it (plus deltas, entities and atmosphere) to
players looking through the near door. The client meshes the snapshot with the vanilla block renderer and draws the mesh
inside the doorway, masked by the **stencil buffer**, with a rigid transform that glues the two doorway planes together.
Walking into the doorway plane teleports the entity with the same transform; the client hides the dimension change.

```
 server                                      client
 ──────                                      ──────
 TardisInteriorManager (SavedData)           BotiClientCache      (snapshots per view, deltas applied)
   └ TardisRecord: cell, both doors, open      ├ SnapshotBlockGetter (vanilla BlockAndTintGetter over a snapshot)
 PortalWatcher                                 ├ BotiMeshBuilder     (vanilla ModelBlockRenderer/FluidRenderer, off-thread)
   ├ who watches which door (scan)             ├ BotiMesh/MeshCache  (GPU buffers, translucent re-sort, throttled rebuild)
   ├ SnapshotCapture ──── BotiSnapshotPayload ─┤ BotiBlockEntities   (BE instances from NBT)
   ├ diff → ───────────── BotiDeltaPayload ────┤ BotiEntities        (detached stand-in entities)
   ├ entities → ───────── BotiEntitiesPayload ─┘ BotiRenderer        (extract → prepare → draw in the stencil)
   └ portal_view chunk ticket                  SeamlessTransition  (no loading screen, arrival cover)
 DoorwayCrossing ──────── BotiCrossingPayload ─►
   └ (teleport) ───────── BotiArrivalPayload ──►
```

## Core abstractions (`portal` package)

| Type | Role |
| --- | --- |
| `PortalShape` | The only definition of a doorway: opening `width` × `height` (blocks), `bottomOffset` (y), `planeOffset` (distance of the doorway plane from the block centre along `FACING`). Rendering, culling, crossing detection and teleporting all call it; nothing else hard-codes doorway numbers. |
| `PortalEndpoint` | Implemented by a block entity that is one end of a doorway: shape, facing, `getDoorOpenAmount(partialTick)` (0..1), TARDIS id, side, position, level. `getOpenSpan` (drawn) and `getPassableSpan` (walkable) say which part of the opening is open, as an `OpenSpan` across its width; the defaults derive both from the open amount, double doors open one half at a time. |
| `DoorState` | The TARDIS's doors: closed → right leaf open → both open → closed, one step per click. Stored on the record and mirrored to both door block entities, which animate each leaf locally. |
| `PortalEndpoints` | Per-level registry of loaded endpoints (both logical sides). The renderer iterates it, so it is not tied to any block or block entity renderer. |
| `DoorPairTransform` | Rotation in 90° steps + translation mapping exterior-door space to interior-door space (`apply`, `invert`, yaw, velocity, direction). It glues the two doorway anchors (bottom-centre of each opening) so that walking *into* one doorway is walking *out of* the other. |

Door-local frame: forward = `facing` (out of the doorway, the viewer's side), up = +Y, right = `facing.getCounterClockWise()`.

## Server side

**Interiors.** `artronindustries:tardis_interiors` is a datapack dimension (void flat generator, custom biome without
precipitation or spawns, dimension type with no sky light, `skybox: none`, fixed time, no raids, respawn anchors off, safe
beds). `TardisInteriorManager` hands out grid cells on a square spiral (`tardis.cellSpacing` apart, default 4096) and
never reuses cells of deleted TARDISes unless `tardis.reuseDeletedCells` is set. The starter room is generated lazily (first
open or `/artron tardis enter`). The manager owns the authoritative door state; both door block entities mirror it.

**Watching.** Every `boti.watcherScanInterval` ticks, players within `boti.watchRadius` of an open door, in front of it,
are subscribed to that door's *view* (`PortalViewKey` = TARDIS + the side the viewer stands on). A view:

* holds a `portal_view` chunk ticket (load + simulate) on the far box, released when the last watcher leaves;
* captures its first snapshot only once those chunks have loaded asynchronously (never generates chunks synchronously);
* sends the full snapshot on subscribe (split into parts above `boti.maxPayloadBytes`);
* sends one `BotiDeltaPayload` per check: positions hinted by `BlockEvent.NeighborNotifyEvent` are diffed every
  `boti.blockDeltaInterval` ticks, and a full sweep every `boti.lightDeltaInterval` ticks also catches light, block entity
  data and changes made without neighbour updates (more than a quarter of the box changed → full snapshot instead);
* refreshes the far-side atmosphere (`PortalEnvironment`: sky/fog colour sampled from environment attributes at the far
  doorway, rain, thunder, time, biome) every `boti.headerRefreshInterval` ticks;
* sends the entities inside the box every `boti.entityUpdateInterval` ticks (vanilla `SynchedEntityData` values when an
  entity is new to the view and once a second);
* is dropped (clear sent to every watcher) when the door closes, the TARDIS is deleted or a door moves; a player is
  unsubscribed when out of range, on dimension change and on logout.

**Snapshot format** (`PortalSnapshot`): box in far-side world coordinates (defined in door-local terms by
`SnapshotBox.inFrontOf`, stored axis-aligned so block states never need rotating); block state ids as palette + bit-packed
indices (`PalettedInts`, no value straddles a long); one byte of packed block/sky light per block; one biome id per
column; NBT of block entities whose type is in the tag `artronindustries:boti_render_block_entities`. The box starts at the
first block layer fully in front of the far doorway plane, so nothing behind the far door can appear in front of the near
doorway. Typical sizes: interior 24×16×24 ≈ 15 KB, exterior 40×24×40 ≈ 58 KB.

**Walk-through** (`DoorwayCrossing`): each tick, entities within 3 blocks of an open doorway plane are tracked; going from
in front of the plane to behind it, inside the opening, while the door is at least half open, moves the entity to the other
door: position, yaw (and head yaw) and velocity through the transform, pitch kept. 10-tick cooldown
(`teleport.crossingCooldown`), `Entity#canTeleport` respected, vehicles/passengers skipped, non-players behind
`teleport.teleportNonPlayers`. Players approaching within `teleport.prewarmDistance` get the destination chunks pre-loaded
with a short-lived ticket.

Security: every BOTI payload is server → client. There are no client → server packets, so clients can never send block
data or door ids.

## Client side

**Meshing.** `SnapshotBlockGetter` implements vanilla's `BlockAndTintGetter` over an immutable copy of the snapshot:
states, light from the packed data (via `getBrightness`), biome tint blended over 3×3 columns, air outside the box. The
vanilla `ModelBlockRenderer` and `FluidRenderer` tessellate it off the render thread into one mesh per
`ChunkSectionLayer` (26.3 has `SOLID`, `CUTOUT`, `TRANSLUCENT`; cutout-mipped no longer exists), so ambient occlusion,
tint, fluids and model quirks match real terrain. Meshes are only rebuilt when the view changes, at most
`boti.maxRebuildsPerFrame` start per frame, and uploads happen on the render thread. Translucent quads are re-sorted when the
camera (mapped into far-side space) moves half a block.

**Render path: stencil, at `RenderLevelStageEvent.AfterOpaqueFeatures`.** 26.3's renderer (Mojang's "renderpearl" API with
OpenGL and Vulkan backends) exposes stencil through NeoForge: `ConfigureMainRenderTargetEvent#enableStencil` adds a
depth-stencil attachment to the main target, `StencilManager` hands out a bit, and `DepthStencilState` carries a NeoForge
`StencilTest`. That is a clean, backend-independent path, so the offscreen fallback from the original plan was not built
(`boti.renderMode` is `STENCIL | DISABLED`).

The stage was chosen because all opaque terrain and opaque entities/block entities are already in the depth buffer (so the
doorway mark is hidden by anything in front of it), translucent terrain, translucent features, particles and weather are
not drawn yet (so they blend and sort against the sealed doorway afterwards), and it fires in both the classic and the
improved-transparency (OIT) paths with an open render pass on the main colour + depth-stencil target. NeoForge requires
uploads to happen before passes open, so the work is split:

1. `ExtractLevelRenderStateEvent` — for every open `PortalEndpoint`: back-facing, distance (`boti.renderDistance`) and
   frustum culling; the doorway quad clipped horizontally by the open amount; the far → near matrix built in double
   precision relative to the camera; backdrop colours.
2. `PrepareRenderBuffersEvent` — mesh uploads/rebuild scheduling/re-sorts, doorway quads, per-door fog UBO, and block
   entities + entity stand-ins submitted into BOTI's own `FeatureRenderDispatcher` (vanilla's prepared frame is in use).
3. `AfterOpaqueFeatures`, per door:
   1. **mark** — doorway quad into the stencil bit, depth-tested, no colour/depth writes;
   2. **backdrop** — inside the bit: quad forced to the far plane (reverse-Z, clip z = 0) with depth test ALWAYS, which
      clears depth inside the doorway and paints the backdrop (interior colour, or the far side's sky → fog gradient);
   3. **far side** — the block mesh with BOTI's stencil-tested block pipelines (the door transform is passed in the
      otherwise unused `TextureMat` slot so fog is computed in the viewer's space), then block entities and entities
      under the `artronindustries:inside_doorway` **pipeline modifier** (NeoForge's `PipelineModifier`: every vanilla
      pipeline used is swapped for a cached clone with the stencil test added — no mixins);
   4. **seal** — the doorway quad's real depth written back and the stencil bit cleared, so particles, entities and
      translucents behind/in front of the doorway sort correctly.
   No doorway is ever rendered inside a doorway (recursion depth 1).

Looking out of a TARDIS, the interior dimension's lightmap has no sky light, so the far side's sky light is folded into
block light scaled by its daylight (rebuilds only when that changes by 1/16), and terrain fades into the far side's fog
colour towards the edge of the box.

**Seamless walk-through** (`SeamlessTransition`). The server announces a crossing (`BotiCrossingPayload`) just before
moving the player. For that transition only, NeoForge's `RegisterDimensionTransitionScreenEvent` supplies a loading screen
that draws nothing; the cached view of the far side is drawn unmasked in its real position ("arrival cover") until the
real chunks are compiled; and the player is released as soon as its chunk is present instead of when its section has
compiled (one access transformer: `ClientPacketListener#notifyPlayerLoaded`). While the camera is in a doorway plane or
just through it before the server's teleport arrives, the far side is drawn full-screen. The server's copy of a player
lags the client by a tick or two, so players are teleported with yaw, pitch and velocity relative to the client's own
(`Relative.ROTATION` + `Relative.DELTA`, the turn being the doorway pair's rotation): speed, sprint and look direction
carry over exactly. A `BotiArrivalPayload` follows the teleport; the client then moves the new player on by the lead its
old one had over the server's crossing position, and copies over the previous-tick position and rotation, hand sway and
view bobbing (access transformer on `ClientAvatarState`) and first-person hands (`LocalPlayer#firstPersonHandsAndItems`),
so the camera neither snaps back nor pauses for a tick. The invisible loading screen is opened as soon as the crossing
is announced (the respawn only updates an open loading screen; opening one forces a frame before the new player is the
camera, which is black), and the server sends the nearest destination chunks ahead of the arrival packet, so the player
is normally released and the screen closed in the same packet batch.

Other dimension changes into or out of interiors (commands, death) keep the normal loading screen.

**Doors that open inwards.** A door that swings in behind its doorway plane would be painted over by the far side.
Its renderer implements `BotiDoorOverlay`; the nearest drawn doorway's own door is submitted again (only the leaves) into
BOTI's feature frame, in near-side camera space, and drawn inside the stencil after the far side's blocks, so the leaves
sort against the far side as if they swung into it. The Hudolin exterior (`HudolinExteriorRenderer`) does this.

**Shader packs.** If Iris or Oculus is installed, doorways use the fallback surface (dark shimmer) unless
`boti.forceWithShaders` is set: shader packs replace the pipelines BOTI relies on. The same fallback is used for
`renderMode = DISABLED` and if no stencil bit is available.

## Configuration

`config/artronindustries-common.toml` (FML 12 has no COMMON type any more; this is a LOCAL config under that name, read
by the server):

| Key | Default | Meaning |
| --- | --- | --- |
| `tardis.cellSpacing` | 4096 | Blocks between interior cells (new TARDISes only). |
| `tardis.interiorY` | 64 | Floor height of interiors. |
| `tardis.reuseDeletedCells` | false | Reuse cells of deleted TARDISes. |
| `boti.interiorSnapshotWidth/Height/Depth` | 24/16/24 | Interior box streamed to viewers outside. |
| `boti.exteriorSnapshotWidth/Height/Depth` | 40/24/40 | Outside box streamed to viewers inside. |
| `boti.watchRadius` | 32 | Distance at which players start receiving a view. |
| `boti.watcherScanInterval` | 5 | Ticks between watcher re-evaluations. |
| `boti.blockDeltaInterval` | 1 | Ticks between block delta checks. |
| `boti.lightDeltaInterval` | 10 | Ticks between full sweeps (light, block entities, silent changes). |
| `boti.headerRefreshInterval` | 20 | Ticks between atmosphere refreshes. |
| `boti.maxPayloadBytes` | 524288 | Snapshots above this are split into parts. |
| `boti.entityUpdateInterval` | 2 | Ticks between entity updates. |
| `boti.maxEntities` | 32 | Entities sent per view per update. |
| `teleport.teleportNonPlayers` | true | Items, mobs, … can pass through doorways. |
| `teleport.crossingCooldown` | 10 | Ticks before an entity can pass through again. |
| `teleport.prewarmDistance` | 3.0 | Distance at which destination chunks are pre-loaded. |

`config/artronindustries-client.toml`:

| Key | Default | Meaning |
| --- | --- | --- |
| `boti.renderMode` | STENCIL | `STENCIL` or `DISABLED` (fallback surface). Takes effect immediately. |
| `boti.renderEntities` | true | Draw entities through doorways. |
| `boti.maxEntities` | 32 | Entities drawn per doorway. |
| `boti.renderBlockEntities` | true | Draw block entities through the nearest doorway. |
| `boti.forceWithShaders` | false | Render the real view even with Iris/Oculus present. |
| `boti.maxRebuildsPerFrame` | 1 | Mesh rebuilds started per frame. |
| `boti.renderDistance` | 64 | Doorways further away are not drawn. |
| `boti.interiorBackdropColor` | 0x05060a | Colour behind interior geometry. |
| `boti.debugTimings` | false | Log rebuild timings at INFO. |

## How the real TARDIS exterior plugs in

The placeholder `test_exterior_door` exists only until the real exterior is ready. To replace it:

1. Make the exterior's block entity implement `PortalEndpoint`:
   * `getPortalShape()` — the exterior's doorway (`new PortalShape(width, height, bottomOffset, planeOffset)`; the plane
     should be where the door leaves are, the opening the clear area inside the frame);
   * `getFacing()` — the direction the doors face;
   * `getDoorOpenAmount(partialTick)` — 0..1 from the door animation (BOTI clips the opening from the left as it grows;
     entities pass once it is at least 0.5);
   * `getTardisId()`, `getPortalSide()` (`EXTERIOR`), `getPortalPos()`, `getPortalLevel()`, `isPortalRemoved()`.
2. Register while loaded: `PortalEndpoints.add(level, this)` in `onLoad()`, `PortalEndpoints.remove(level, this)` in
   `onChunkUnloaded()` and `setRemoved()`.
3. Link and keep the server in charge of the door: on placement call
   `TardisInteriorManager.get(server).create(level, pos, facing, shape)` (or `linkExterior(...)` for an existing TARDIS);
   open/close through `TardisInteriorManager#setDoorOpen` and mirror the state it pushes (see `PortalDoorBlockEntity`
   for a complete example, including `onLoad` reconciliation and unlinking in `preRemoveSideEffects`).
4. Keep the collision clear in front of the doorway plane so entities can reach it (the placeholder's only collision is a
   thin back panel).

Nothing in the renderer, the watcher, the crossing detector or the teleport needs to change; they all read `PortalShape`
and `PortalEndpoint`. The exterior model/renderer is drawn normally — BOTI draws on top of the doorway after opaque
features, so the model's door frame occludes the doorway correctly.

## Testing

| What | How |
| --- | --- |
| Transform, cell layout, snapshot encoding | `./gradlew test` (JUnit; 97 tests, incl. all 16 facing pairs) |
| Allocation / linking / deletion | `./gradlew runGameTestServer` (the GameTest server never creates datapack dimensions, so interior checks live in the smoke test) |
| Dedicated server end to end | `./gradlew runServer -Partronindustries.smokeTest=true`: interior generation, door sync, snapshot capture, block deltas, item walk-through, close/delete while streaming. `-Partronindustries.smokeTest=keepopen` leaves a TARDIS open; the next normal run checks it survived the restart. |
| Rendering and walk-through on a real client | `./gradlew runClient -Partronindustries.clientSmokeTest=true` with a world named `botitest` in `run/client/saves`: scripted camera poses, screenshots in `run/client/screenshots/boti_*.png` (doorway from several angles, matched-viewpoint comparison with the real interior, block entities, inside → outside at noon/dusk/night with mobs, sprinting in and walking out frame by frame, F3 line), then quits. |
| Live stats | F3 line "BOTI: N views (KB), rebuild ms, draw ms / doorways"; `/artronclient boti stats`; `/artronclient boti debug here|off` floats the cached mesh in front of you. |

Measured on an RTX 5070 / i9-12900K (OpenGL): interior snapshot capture 9–14 ms (once per subscribe), exterior 9 ms after
async chunk load; deltas 0.03–0.6 ms; full sweeps 1–3 ms per view every 10 ticks; mesh rebuild 2–24 ms off-thread; BOTI
draw recording 0.02–0.07 ms of CPU per frame for 1–2 doorways.

## Known limitations

* Only the OpenGL backend has been exercised. The Vulkan backend shares the API and shaders follow vanilla's
  conventions, but it is untested.
* Block entities and entities are drawn only through the nearest open doorway (one BOTI feature frame per frame).
* Stand-in entities are attached to the client level for their renderers but positioned in far-side coordinates;
  renderers that query the level around themselves (rare) see the near side. Name tags face the near-side camera.
* Weather particles and the real sky (sun, moon, stars, clouds) are not drawn through doorways; the backdrop is a sky → fog
  gradient that darkens with rain.
* Biome tint is blended over 3×3 columns, not the player's biome blend setting. Cardinal (face) shading uses the default
  profile, even for Nether-like far sides.
* A player arriving through a doorway is held for the few ticks until its chunk reaches the client (rendering is covered by
  the cached view, so there is no void or loading screen, but movement pauses briefly on slow connections).
* Sodium/Embeddium were not available for testing; the mesh does not depend on the chunk renderer, but their own pipeline
  handling has not been checked.
* Multiplayer with several watchers is handled by the watcher (per-view watcher sets, shared snapshot encoding) but was
  only exercised with one player.
