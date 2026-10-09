# Artron Industries

A Minecraft mod for NeoForge.

## Requirements

- Minecraft 26.3
- NeoForge 26.3.0.57-beta or newer
- Java 25

## Building

```sh
./gradlew build
```

The built jar is written to `build/libs/`.

## Development

| Task | Description |
| --- | --- |
| `./gradlew runClient` | Launch a development client |
| `./gradlew runServer` | Launch a development server |
| `./gradlew runClientData` | Run data generators into `src/generated/resources` |
| `./gradlew runGameTestServer` | Run game tests |

## TARDIS interiors and BOTI

TARDIS interiors live in their own dimension and are visible through the doors ("bigger on the inside"); walking through
an open door is seamless. Architecture, configuration, testing and how the real exterior plugs in:
[docs/BOTI.md](docs/BOTI.md).

Blocks: `artronindustries:tardis` (placing one creates a TARDIS) and `artronindustries:interior_door` (placeholder;
placing one inside a TARDIS moves its interior door there). A TARDIS's exterior and interior are attributes of its block
entity, `exterior` and `interior` (ids; defaults `artronindustries:hudolin` and `artronindustries:starter`), which an item
can set: `/give @s artronindustries:tardis[block_entity_data={id:"artronindustries:tardis",exterior:"artronindustries:hudolin"}]`.
Commands (`/artron` or `/artronindustries`, operators):
`tardis create [exterior] [interior] | list | info [id] | enter <id> | exit [id] | door <id> open|close | delete <id>`.

| Task | Description |
| --- | --- |
| `./gradlew test` | Unit tests (door transform, cell layout, snapshot encoding) |
| `./gradlew runServer -Partronindustries.smokeTest=true` | Dedicated-server smoke test, stops by itself |
| `./gradlew runClient -Partronindustries.clientSmokeTest=true` | Scripted client walkthrough with screenshots (needs a world named `botitest`) |

## License

Licensed under the [MIT License](LICENSE).
