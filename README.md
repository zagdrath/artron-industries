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

## License

Licensed under the [MIT License](LICENSE).
