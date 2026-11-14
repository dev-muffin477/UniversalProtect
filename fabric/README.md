# UniversalProtect, Fabric edition

The server-side Fabric mod. See the [main README](../README.md) and the [wiki](https://github.com/dev-muffin477/UniversalProtect/wiki) for features and usage.

| | |
| :--- | :--- |
| Server | Fabric Loader 0.20.0 or newer |
| Dependency | [Fabric API](https://modrinth.com/mod/fabric-api) |
| Java | 25 |
| Build | Gradle with Fabric Loom (wrapper included) |
| Install | `mods/`, config in `config/universalprotect/` |

```bash
./gradlew build          # result: build/libs/ (use the jar without -sources)
```

All database drivers are bundled. World names are dimension IDs such as `minecraft:overworld`. Versions of Minecraft, Loader, Loom and Fabric API are set in `gradle.properties` (see <https://fabricmc.net/develop>).

Code: `src/main/java/de/muffin/universalprotect/`. Events are handled in `Hooks.java`, Minecraft-specific access in `Mc.java`, and `mixin/` contains the mixins for explosions, fire, decay and more.
