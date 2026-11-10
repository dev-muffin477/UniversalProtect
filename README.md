# UniversalProtect

**UniversalProtect** is an asynchronous data logging, inspection, and anti-griefing protection plugin for Spigot and Paper servers.

## Comparison

| Feature / Comparison | UniversalProtect | CoreProtect |
| :--- | :---: | :---: |
| **Multi-Platform (Paper & Fabric)** | ✔ Yes (Native) | ✖ No (Spigot/Paper only) |
| **New MC Version Updates** | ✔ Immediate / Fast Port | ✖ Delayed / Extended Waits |
| **Modern Databases (DuckDB, ClickHouse)** | ✔ Supported | ✖ Not supported |
| **Live DB Migration (`/up migrate-db`)** | ✔ Yes (No restart needed) | ✖ No |
| **Client Mod API (e.g., Watson)** | ✔ Yes (Integrated) | ✖ No / Limited |

## Features
* **Asynchronous Execution:** Multi-threaded database operations to preserve server TPS.
* **CoreProtect-Style Commands:** Supports aliases (`/up i`, `/up l`, `/up rb`, `/up rs`, `/up near`, `/up undo`).
* **Compressed Storage:** Automatically compresses container and sign data using GZIP + Base64.
* **Environmental Logging:** Tracks explosions (`#tnt`, `#creeper`), fire spread (`#fire`), and liquid updates (`#water`, `#lava`).
* **Modern Interface:** Powered by Adventure MiniMessage with interactive teleportation coordinates.

---

## Requirements & Building

* **Java Version:** 17 or higher
* **Target Server:** Paper / Spigot (1.20+ or API 26.3+)
* **Build System:** Apache Maven

### Build from Source
To compile the plugin JAR manually, run:
```bash
mvn clean package
