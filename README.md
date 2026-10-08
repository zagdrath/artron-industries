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

Test blocks (placeholders until the real exterior exists): `artronindustries:test_exterior_door` (placing one creates a
TARDIS) and `artronindustries:interior_door`. Commands (`/artron` or `/artronindustries`, operators):
`tardis create | list | info [id] | enter <id> | exit [id] | door <id> open|close | delete <id>`.

| Task | Description |
| --- | --- |
| `./gradlew test` | Unit tests (door transform, cell layout, snapshot encoding) |
| `./gradlew runServer -Partronindustries.smokeTest=true` | Dedicated-server smoke test, stops by itself |
| `./gradlew runClient -Partronindustries.clientSmokeTest=true` | Scripted client walkthrough with screenshots (needs a world named `botitest`) |

## License

Licensed under the [MIT License](LICENSE).
