# UniversalProtect (Fabric Edition)

**UniversalProtect** is an asynchronous logging, inspection and rollback mod for **Fabric servers**. It is a CoreProtect-style alternative that runs **server-side only**: players do not need to install anything.

> **Status:** The Fabric edition is a new port of the Paper plugin. Please report bugs in the issue tracker.




## Comparison

| Feature / Comparison | UniversalProtect | CoreProtect |
| :--- | :---: | :---: |
| **Multi-Platform (Paper & Fabric)** | ✔ Yes (Native) | ✖ No (Spigot/Paper only) |
| **New MC Version Updates** | ✔ Immediate / Fast Port | ✖ Delayed / Extended Waits |
| **Modern Databases (DuckDB, ClickHouse)** | ✔ Supported | ✖ Not supported |
| **Live DB Migration (`/up migrate-db`)** | ✔ Yes (No restart needed) | ✖ No |
| **Client Mod API (e.g., Watson)** | ✔ Yes (Integrated) | ✖ No / Limited |

## Features

* **Asynchronous execution:** Database work runs on a dedicated thread to preserve server TPS.
* **CoreProtect-style commands:** `/up inspect`, `lookup`, `near`, `rollback`, `restore`, `undo`, `purge`, `status` with short aliases (`/up i`, `/up l`, `/up rb`, `/up rs`). `/universalprotect` and `/uprotect` work as aliases for `/up`.
* **Four database engines:** DuckDB (default), SQLite, MySQL and ClickHouse. All drivers are bundled in the mod. Switch live with `/up migrate-db`.
* **Rollback and restore:** Blocks, containers, inventories, entity kills and spawns, with a detailed `#preview` and `/up undo`.
* **Environmental logging:** Tracks explosions (`#tnt`, `#creeper`), fire (`#fire`), liquids (`#water`, `#lava`), leaf decay and more under system users.
* **Blacklist and per-world configuration:** `blacklist.txt` and a `<world>.yml` file per world.
* **Modern chat interface:** Colored output with clickable teleport coordinates and hover details.
* **API:** Modelled on CoreProtect API v13, plus a networking API for client mods such as Watson.

---

## Requirements

* **Minecraft:** 26.3
* **Mod loader:** [Fabric Loader](https://fabricmc.net/use/server/) 0.20.0 or newer
* **Dependency:** [Fabric API](https://modrinth.com/mod/fabric-api)
* **Java:** 25
* **Environment:** Server-side only

## Installation

1. Put `UniversalProtect-<version>.jar` and the Fabric API jar into the `mods` folder of your Fabric server.
2. Start the server once. The configuration is created in `config/universalprotect/`.
3. Give yourself permissions (see below) and run `/up status`.

## Permissions

Permissions are checked through [LuckPerms](https://luckperms.net/) or any mod that provides the Fabric Permissions API. Without one, operators (permission level 2) can use the admin commands.

| Permission | Description |
| :--- | :--- |
| `universalprotect.use` | Lookup, inspector and status |
| `universalprotect.admin` | Rollback, restore, purge, reload, alerts and migration |
| `universalprotect.networking` | Lets client mods receive results |

---

## Building from Source

* **Java:** 25 (JDK)
* **Build system:** Gradle with Fabric Loom (the Gradle wrapper is included)

```bash
./gradlew build
```

The finished mod is written to `build/libs/`. Use the jar without the `-sources` suffix.

The versions of Minecraft, Fabric Loader, Loom and Fabric API are set in `gradle.properties`. Current values for each Minecraft version are listed at <https://fabricmc.net/develop>.

## Notes

* World names are dimension IDs such as `minecraft:overworld`. Aliases like `world`, `nether` and `end` are understood in commands and file names.
* The data of the Paper edition cannot be imported into the Fabric edition.

## License

See the `LICENSE` file.

UniversalProtect is an independent project and is not affiliated with or endorsed by the CoreProtect authors.
