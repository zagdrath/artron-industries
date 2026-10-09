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

**Watching.** Every `boti.watcherScanInterval` ticks, players within `boti.watchRadius` of a door, in front of it, are
subscribed to that door's *view* (`PortalViewKey` = TARDIS + the side the viewer stands on). Shut doors are watched too
(interiors are generated when the TARDIS is placed), and the client builds a shut door's mesh ahead, so the far side is
there the moment the doors open; a doorway whose view has no mesh yet is not drawn at all rather than show its bare
backdrop. A view:

* holds a `portal_view` chunk ticket (load + simulate) on the far box, released when the last watcher leaves;
* captures its first snapshot only once those chunks have loaded asynchronously (never generates chunks synchronously);
* sends the full snapshot on subscribe (split into parts above `boti.maxPayloadBytes`);
* sends one `BotiDeltaPayload` per check: positions hinted by `BlockEvent.NeighborNotifyEvent` are diffed every
  `boti.blockDeltaInterval` ticks, and a full sweep every `boti.lightDeltaInterval` ticks also catches light, block entity
  data and changes made without neighbour updates (more than a quarter of the box changed → full snapshot instead);
* refreshes the far-side atmosphere (`PortalEnvironment`: sky/fog colour and fog distances sampled from environment
  attributes at the far doorway, rain, thunder, time, biome, and the lightmap inputs: sky light factor and colour, ambient colour, block light
  tint) every `boti.headerRefreshInterval` ticks;
* sends the entities inside the box every `boti.entityUpdateInterval` ticks (vanilla `SynchedEntityData` values when an
  entity is new to the view and once a second);
* is dropped (clear sent to every watcher) when the door closes, the TARDIS is deleted or a door moves; a player is
  unsubscribed when out of range, on dimension change and on logout.

**Snapshot format** (`PortalSnapshot`): box in far-side world coordinates (defined in door-local terms by
`SnapshotBox.inFrontOf`, stored axis-aligned so block states never need rotating); block state ids as palette + bit-packed
indices (`PalettedInts`, no value straddles a long); one byte of packed block/sky light per block; one biome id per
column; NBT of block entities whose type is in the tag `artronindustries:boti_render_block_entities`. The box starts at the
first block layer fully in front of the far doorway plane, so nothing behind the far door can appear in front of the near
doorway. Typical sizes: interior 24×16×24 ≈ 15 KB, exterior 40×24×40 ≈ 58 KB (the defaults are now 48×24×48 and 64×32×64).

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
states, the far side's own light levels from the packed data (via `getBrightness`), biome tint blended over 3×3 columns,
air outside the box. The box is split into 16³ sections (`SnapshotSections`, box-local, the last one on each axis cut
short), and the vanilla `ModelBlockRenderer` and `FluidRenderer` tessellate them off the render thread into one mesh per
section and `ChunkSectionLayer` (26.3 has `SOLID`, `CUTOUT`, `TRANSLUCENT`; cutout-mipped no longer exists), so ambient
occlusion, tint, fluids and model quirks match real terrain. A new snapshot rebuilds every section; a delta only the
sections around the blocks it changed (a block on a section border also dirties its neighbours, since culling, AO and
fluid heights look one block around). Rebuilds start only when the view changes, at most `boti.maxRebuildsPerFrame` per
frame, and uploads happen on the render thread.

Each frame, a doorway draws only the sections in front of the far doorway plane, inside the view frustum and inside the
pyramid from the camera through the open part of the opening (doorway pairs only turn in quarter turns, so section bounds
stay axis-aligned on the near side): opaque layers nearest first, translucent farthest first. Each visible section's
translucent quads are re-sorted when the camera (mapped into far-side space) has moved half a block since its last sort.

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

**Lighting.** Every view has its own vanilla `Lightmap` (`BotiLightmaps`), lit like the far side: sky light strength and
colour, ambient colour and block light tint come from the far side's `PortalEnvironment.Light`, everything that belongs to
the viewer (gamma, night vision, darkness, block light flicker, boss fog) from the viewer's own lightmap. It is redrawn
when the viewer's is (once a tick) or the far side's light changes, and the block mesh samples it, so night falls through a
doorway with no rebuild, moonlight is tinted like vanilla's and a far side without sky light (or with a dim ambient, like
the Nether) looks as it does there. Block entities and entities seen through a doorway are drawn by vanilla's feature
renderers, which bind the main lightmap themselves; looking out of a TARDIS (whose dimension has no sky light) their sky
light is still folded into block light, scaled by the far side's daylight.

**The ground to the horizon.** Looking out, the streamed box ends long before vanilla's terrain would, so the ground is
carried on to the viewer's render distance (at most 256 blocks) by a **skirt** (`SkirtBlockGetter`,
`BotiMeshBuilder#buildSkirt`): over a box in front of the far doorway like the snapshot's, every column outside the
snapshot gets the ground of the nearest edge column (its blocks up to the same height; plants, leaves and logs are not
ground, so a tree at the edge is not drawn out into a wall), meshed with the vanilla renderers like the snapshot itself
(tint, water and light included), with the sides of steps between columns down to the lowest neighbour. It is built off
the render thread (about 0.1 s) for each new snapshot or render distance, in 64-block sections that are culled like the
snapshot's, and drawn with it (opaque layers of both, then the skirt's water, then the box's). The far side is fogged as
vanilla fogs it: the far side's own environmental fog distances (pulled in by rain the same way) and the viewer's
render-distance fog, which the skirt reaches, so terrain meets the horizon as it does outside.

**Seamless walk-through** (`SeamlessTransition`). The server announces a crossing (`BotiCrossingPayload`) just before
moving the player. For that transition only, NeoForge's `RegisterDimensionTransitionScreenEvent` supplies a loading screen
that draws nothing; the cached view of the far side is drawn unmasked in its real position ("arrival cover") until the
real chunks are compiled; and the player is released as soon as its chunk is present instead of when its section has
compiled (one access transformer: `ClientPacketListener#notifyPlayerLoaded`). The client decides the moment
the eye crosses (`DoorwayEye`): every frame, the eye's path since the last frame is tested against the open part of the
opening, and the far side fills the screen from the frame it crosses until the teleport arrives (or for a second, if the
server refuses the crossing), however far the eye gets before then. It also fills the screen while the eye is within the
near-plane distance in front of the plane, where the doorway quad would be cut. Walking out backwards, the far side drawn
full-screen gets the police box stand-in, so looking back shows the box you are leaving. The server's copy of a player
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

**The outside's sky.** Looking out of a TARDIS, the nearest doorway that faces an overworld-like sky also gets that
sky (`BotiSky`): vanilla's sky disc, sunrise/sunset glow, sun, moon, stars and clouds, drawn inside the doorway's stencil
after the backdrop and before the far side's blocks, turned with the door pair. The server samples the far side's own
environment attributes at the doorway (sun, moon and star angles, star brightness, moon phase, sunrise and cloud colours,
cloud height, game time) into `PortalEnvironment.Sky`, refreshed with the rest of the environment; in between, the sun,
moon and stars keep moving at the rate seen between the last two samples and the clouds drift with the far game time.
Vanilla's sky renderer is borrowed (the interior has no sky; three of its private draw methods are opened by access
transformer, as `SkyRenderer#render` opens its own pass); the clouds have their own `CloudRenderer`, prepared before the
frame. Sky and clouds fade into the far side's fog colour like vanilla's. Off with `boti.renderSky`.

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
| `boti.interiorSnapshotWidth/Height/Depth` | 48/24/48 | Interior box streamed to viewers outside. |
| `boti.exteriorSnapshotWidth/Height/Depth` | 64/32/64 | Outside box streamed to viewers inside. |
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
| `boti.renderSky` | true | Looking out, draw the outside's sky, sun, moon, stars and clouds in the doorway. |
| `boti.forceWithShaders` | false | Render the real view even with Iris/Oculus present. |
| `boti.maxRebuildsPerFrame` | 1 | Mesh rebuilds started per frame. |
| `boti.renderDistance` | 64 | Doorways further away are not drawn. |
| `boti.interiorBackdropColor` | 0x05060a | Colour behind interior geometry. |
| `boti.debugTimings` | false | Log rebuild timings at INFO. |
| `interior.humVolume` | 0.6 | Volume of the interior hum (0 = off), on top of the Ambient/Environment slider. |

## Exteriors and interiors

The TARDIS block (`artronindustries:tardis`, `TardisBlock` / `TardisBlockEntity`) carries two attributes, saved on its
block entity and in its `TardisRecord`:

* `exterior` — a `TardisExterior` from `TardisExteriors` (default `artronindustries:hudolin`). It supplies the doorway
  (`PortalShape`) and the outline and collision of the three blocks the box stands on; its model is drawn by the TARDIS
  block entity renderer (`HudolinExteriorRenderer`, the only one so far). The block entity's exterior wins: on load the
  record is brought in line with it.
* `interior` — a `TardisInterior` from `TardisInteriors` (default `artronindustries:victorian_parlour`). It gives the
  interior door's offset from the cell origin, its facing and its opening, and generates the interior the first time it
  is needed. The record's interior wins, since it may already be built. `artronindustries:starter` (built in code by
  `InteriorGenerator`) is the room the BOTI tests use; records from before interiors could be chosen load as it.

Both are set from the item's `block_entity_data` when placed (or by `/artron tardis create [exterior] [interior]`).
Unknown ids in saves load as the defaults. To add an exterior, subclass `TardisExterior` and register it in
`TardisExteriors`; to add an interior, register a `TardisInterior` in `TardisInteriors`.

### Interior hums

An interior can have a hum (`TardisInterior#withHum`, a sound event in `ArtronSounds`), which loops for every player
inside it. The server tells each player which interior's cell they are in (`InteriorPresence`, checked every 5 ticks,
`InteriorPresencePayload` sent only on change), and the client (`InteriorHum`) fades the hum in over 1.5 s on entering and
out on leaving. It is not positional and plays on the ambient channel. The Victorian Parlour hums like the 1996 TARDIS.

For a gapless loop the sound must be `"stream": false` in `sounds.json`: vanilla then loops it in OpenAL, where a
streamed sound is reopened at the end. The file itself must also loop seamlessly. The parlour's hum
(`sounds/interior_hum/victorian_parlour.ogg`, 18.216 s) was cut from a 10-minute recording of a repeating 3.643229 s cycle:
5 whole cycles (803,332 samples at 44.1 kHz, within 0.03 of a sample of exact, so it does not drift), taken from the steady
middle at the quietest point of the cycle, with the first 0.25 s crossfaded with the audio that followed the cut in the
recording, so the last sample leads into the first as it did originally. Ogg Vorbis records the exact length, so it decodes
to exactly that many samples with no padding.

### Door sounds

Each end of the doorway has its own door sounds (`DoorSounds`: open, close and `swingTicks`), played where that door
stands on every state change (opening for each leaf, closing once): the exterior's (`TardisExterior#doorSounds`) outside,
the interior's (`TardisInterior#withDoorSounds`) inside, the vanilla iron door for either that has none. The door's leaves
swing for `swingTicks`, which is how long its sounds last, so the sound starts and stops with the movement; the door
block entity takes it from its side's sounds on the server, saves it and syncs it, so the server's walk-through checks
(passable at half open) and the client's animation agree. Doors with no sounds of their own swing in 10 ticks.

The Hudolin police box and the Victorian Parlour use the 1996 TARDIS's, cut between the silences around them in a
sound-effects compilation, folded to mono (positional sounds must be) and levelled to the same peak: the police box's
short latch clunks (0.38 s and 0.34 s; its doors swing in 10 ticks), and the parlour's interior doors, which swing in
50 ticks (2.5 s). Their recordings are about 5 s long, so each was cut to exactly 2.5 s (110,250 samples at 44.1 kHz)
by keeping its start and its natural run-down and removing part of the steady middle, joined with a 0.2 s equal-power
crossfade at the point where the two sides line up best.

### Interior templates

Template interiors (`TemplateInterior`) are Sponge schematics (`.schem`, versions 2 and 3, as saved by WorldEdit and
FAWE) in `data/<namespace>/tardis_interior/<name>.schem`, so a data pack can replace one. `SpongeSchematic` turns the
file into vanilla structure template NBT and the game's own data fixer and structure placement take it from there, so
schematics from older versions are upgraded like any structure. The schematic's lowest corner goes at the cell origin and
it is placed as saved (no shape updates). Magenta wool on a corner of the bounding box is a selection marker and is left
out.

The door is part of the build: registering the interior names the box of blocks that make up the doors (corners in
schematic coordinates), the way they face (into the room) and how far behind the front of the box the doorway plane is.
After placement those blocks become `interior_doorway` cells (`InteriorDoorwayBlock`): invisible, colliding like the block
they replaced while shut, not at all while open, and clickable to open and shut the doors. The master cell (front bottom
left) holds an `InteriorDoorwayBlockEntity`, the interior door, which keeps the replaced blocks and draws them as two
leaves (`InteriorDoorwayRenderer`): the left half, as seen from the room, is hinged on its left edge, the rest on its
right edge, and both swing 90 degrees out into the room. The left leaf opens with the exterior's right leaf, since that
is where the exterior's right half comes out. Block entity data of the replaced blocks is not kept.

The two ends of a doorway need not be the same size: each side's opening is its own `PortalShape` (which can now sit off
the block centre, `lateralOffset`, for even widths), glued at the bottom centre with no scaling. Looking out of the
parlour's 4x4 door shows a 4x4 piece of the world at real scale; anything walking out of a wider or taller doorway than
the far one is put back inside the far opening (`DoorwayCrossing.intoOpening`), so it steps out of the police box's doors.

The Victorian Parlour's doors are the 4x4 gray leaves (and the button panelling in front) at x 12-15, y 2-5, z 38-39 of
its schematic, facing north, with the plane on the front of the gray blocks.

### Other doorway ends

Anything else that should act as a doorway end works the same way as `TardisBlockEntity`:

1. Make its block entity implement `PortalEndpoint`:
   * `getPortalShape()` — the exterior's doorway (`new PortalShape(width, height, bottomOffset, planeOffset)`; the plane
     should be where the door leaves are, the opening the clear area inside the frame);
   * `getFacing()` — the direction the doors face;
   * `getDoorOpenAmount(partialTick)` — 0..1 from the door animation (BOTI clips the opening from the left as it grows;
     entities pass once it is at least 0.5);
   * `getTardisId()`, `getPortalSide()` (`EXTERIOR`), `getPortalPos()`, `getPortalLevel()`, `isPortalRemoved()`.
2. Register while loaded: `PortalEndpoints.add(level, this)` in `onLoad()`, `PortalEndpoints.remove(level, this)` in
   `onChunkUnloaded()` and `setRemoved()`.
3. Link and keep the server in charge of the door: on placement call
   `TardisInteriorManager.get(server).create(level, pos, facing, exterior, interior)` (or `linkExterior(...)` for an existing TARDIS);
   open/close through `TardisInteriorManager#setDoorOpen` and mirror the state it pushes (see `PortalDoorBlockEntity`
   for a complete example, including `onLoad` reconciliation and unlinking in `preRemoveSideEffects`).
4. Keep the collision clear in front of the doorway plane so entities can reach it (the interior door's only collision
   is a thin back panel).

Nothing in the renderer, the watcher, the crossing detector or the teleport needs to change; they all read `PortalShape`
and `PortalEndpoint`. The exterior model/renderer is drawn normally — BOTI draws on top of the doorway after opaque
features, so the model's door frame occludes the doorway correctly.

## Testing

| What | How |
| --- | --- |
| Transform, cell layout, snapshot encoding, schematic reading, doorway geometry | `./gradlew test` (JUnit; 119 tests, incl. all 16 facing pairs, the parlour schematic and the section grid) |
| Allocation / linking / deletion | `./gradlew runGameTestServer` (the GameTest server never creates datapack dimensions, so interior checks live in the smoke test) |
| Dedicated server end to end | `./gradlew runServer -Partronindustries.smokeTest=true`: interior generation, door sync, snapshot capture, block deltas, item walk-through, close/delete while streaming, the Victorian Parlour (template placement, doorway leaves and collision, exits put in front of the police box). `-Partronindustries.smokeTest=keepopen` leaves a TARDIS open; the next normal run checks it survived the restart. |
| Rendering and walk-through on a real client | `./gradlew runClient -Partronindustries.clientSmokeTest=true` with a world named `botitest` in `run/client/saves`: scripted camera poses, screenshots in `run/client/screenshots/boti_*.png` (doorway from several angles, matched-viewpoint comparison with the real interior, block entities, inside → outside at noon/dusk/night with mobs, sprinting in and walking out frame by frame, F3 line), then quits. |
| Live stats | F3 line "BOTI: N views (KB), rebuild ms / sections, draw ms / doorways, sections"; `/artronclient boti stats`; `/artronclient boti debug here|off` floats the cached mesh in front of you. |

Measured on an RTX 5070 / i9-12900K (OpenGL): interior snapshot capture 9–14 ms (once per subscribe), exterior 9 ms after
async chunk load; deltas 0.03–0.6 ms; full sweeps 1–3 ms per view every 10 ticks; mesh rebuild 2–24 ms off-thread; BOTI
draw recording 0.02–0.07 ms of CPU per frame for 1–2 doorways.

## Known limitations

* Only the OpenGL backend has been exercised. The Vulkan backend shares the API and shaders follow vanilla's
  conventions, but it is untested.
* Block entities and entities are drawn only through the nearest open doorway (one BOTI feature frame per frame).
* Stand-in entities are attached to the client level for their renderers but positioned in far-side coordinates;
  renderers that query the level around themselves (rare) see the near side. Name tags face the near-side camera.
* Weather particles are not drawn through doorways. The outside's sky is drawn through one doorway per frame (the
  nearest looking out); others show the sky → fog gradient backdrop. End and Nether skyboxes are not drawn.
* Terrain beyond the streamed box (`exteriorSnapshot*`) is made up from its edge (the ground skirt): hills, buildings and
  water beyond it are not there, and changes near the edge only reach the skirt with the next full snapshot.
* Biome tint is blended over 3×3 columns, not the player's biome blend setting. Cardinal (face) shading uses the default
  profile, even for Nether-like far sides.
* A player arriving through a doorway is held for the few ticks until its chunk reaches the client (rendering is covered by
  the cached view, so there is no void or loading screen, but movement pauses briefly on slow connections).
* Sodium/Embeddium were not available for testing; the mesh does not depend on the chunk renderer, but their own pipeline
  handling has not been checked.
* Multiplayer with several watchers is handled by the watcher (per-view watcher sets, shared snapshot encoding) but was
  only exercised with one player.
