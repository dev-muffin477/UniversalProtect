# UniversalProtect (Paper Edition)

**UniversalProtect** is an asynchronous logging, inspection and rollback plugin for **Paper servers**. It is a CoreProtect-style alternative with modern database engines and live migration.

> A Fabric edition with the same feature set is available as a separate server-side mod: [UniversalProtect-Fabric](https://github.com/dev-muffin477/UniversalProtect-Fabric).

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
* **Four database engines:** DuckDB (default), SQLite, MySQL and ClickHouse. All drivers are downloaded by Paper automatically. Switch live with `/up migrate-db`.
* **Rollback and restore:** Blocks, containers, inventories, entity kills and spawns, with a detailed `#preview` and `/up undo`.
* **Environmental logging:** Tracks explosions (`#tnt`, `#creeper`), fire (`#fire`), liquids (`#water`, `#lava`), leaf decay and more under system users.
* **Blacklist and per-world configuration:** `blacklist.txt` and a `<world>.yml` file per world.
* **Modern chat interface:** Colored output with clickable teleport coordinates and hover details.
* **API:** Modelled on CoreProtect API v13, plus a networking API for client mods such as Watson.

---

## Requirements

* **Minecraft:** 26.3
* **Server:** [Paper](https://papermc.io/) (API 26.3)
* **Java:** 25

## Installation

1. Put `UniversalProtect.jar` into the `plugins` folder of your Paper server.
2. Start the server once. The configuration is created in `plugins/UniversalProtect/`.
3. Give yourself permissions (see below) and run `/up status`.

## Permissions

Permissions work with any permissions plugin such as [LuckPerms](https://luckperms.net/). By default, operators have all permissions.

| Permission | Description |
| :--- | :--- |
| `universalprotect.use` | Lookup, inspector and status |
| `universalprotect.admin` | Rollback, restore, purge, reload, alerts and migration |
| `universalprotect.networking` | Lets client mods receive results |

---

## Building from Source

* **Java:** 25 (JDK)
* **Build system:** Apache Maven

```bash
mvn clean package
```

The finished plugin is written to `target/UniversalProtect.jar`.

## Notes

* World names are the Bukkit world names (e.g. `world`, `world_nether`, `world_the_end`).
* The data of the Fabric edition cannot be imported into the Paper edition.

## License

See the `LICENSE` file.

UniversalProtect is an independent project and is not affiliated with or endorsed by the CoreProtect authors.
