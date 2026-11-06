# UniversalProtect

**UniversalProtect** is an asynchronous data logging, inspection, and anti-griefing protection plugin for Spigot and Paper servers.

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
