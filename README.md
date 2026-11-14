<div align="center">

# UniversalProtect

**Logging, inspection and rollback for Minecraft servers**

A CoreProtect-style alternative for **Paper** and **Fabric**, with modern databases and live migration.

![Minecraft](https://img.shields.io/badge/Minecraft-26.3-2e7d32?style=for-the-badge) ![Paper](https://img.shields.io/badge/Paper-plugin-1565c0?style=for-the-badge) ![Fabric](https://img.shields.io/badge/Fabric-mod-6d4c41?style=for-the-badge) ![Java](https://img.shields.io/badge/Java-25-37474f?style=for-the-badge) ![License](https://img.shields.io/badge/License-GPLv3-455a64?style=for-the-badge)

[**Documentation (Wiki)**](https://github.com/dev-muffin477/UniversalProtect/wiki) · [**Changelog**](CHANGELOG.md) · [**Paper download**](https://modrinth.com/plugin/universalprotect) · [**Fabric download**](https://modrinth.com/mod/universalprotect-fabric-edition)

</div>

---

## Editions

One repository, one version number, two editions with the same features. Both run **server-side only**, players do not need to install anything.

| | Paper | Fabric |
| :--- | :--- | :--- |
| Type | Plugin | Server-side mod |
| Folder | [`paper/`](paper) | [`fabric/`](fabric) |
| Build | Maven | Gradle (Fabric Loom) |
| Needs | Paper 26.3 | Fabric Loader 0.20.0+ and Fabric API |
| Install | `plugins/` | `mods/` |
| Config | `plugins/UniversalProtect/` | `config/universalprotect/` |

Differences between the editions are listed on the wiki page [Platforms](https://github.com/dev-muffin477/UniversalProtect/wiki/Platforms).

## Features

* **Asynchronous:** database work runs on a dedicated thread to protect the server TPS.
* **CoreProtect-style commands:** `/up inspect`, `lookup`, `near`, `rollback`, `restore`, `undo`, `purge` with short aliases (`/up i`, `/up l`, `/up rb`, `/up rs`).
* **Four database engines:** DuckDB (default), SQLite, MySQL and ClickHouse. Switch live with `/up migrate-db`, no restart needed.
* **Rollback and restore:** blocks, containers, inventories, entity kills and spawns, `/fill`, `/setblock` and `/clone`, with a detailed `#preview` and `/up undo`.
* **Investigation and alerts:** `/up investigate`, `/up timeline`, X-ray and anomaly alerts, a staff audit log, and Discord notifications.
* **Storage tools:** `/up stats` shows the space used by every log type, `/up clear` deletes a log type or its old entries, `/up purge` and `/up compact` keep the database small.
* **Creative inventory logging:** items taken from the creative inventory are logged and can be switched off per world.
* **CoreProtect import:** `/up import-coreprotect` brings your old history along.
* **Blacklist and per-world configuration:** decide what is not logged, per world.
* **API:** modelled on CoreProtect API v13, plus a networking API for client mods such as Watson.

## Comparison

| Feature | UniversalProtect | CoreProtect |
| :--- | :---: | :---: |
| Paper and Fabric from one project | ✔ | ✖ (Spigot/Paper only) |
| Fast updates for new Minecraft versions | ✔ | ✖ (often delayed) |
| Modern databases (DuckDB, ClickHouse) | ✔ | ✖ |
| Live database migration (`/up migrate-db`) | ✔ | ✖ |
| Storage statistics and clearing per log type | ✔ | ✖ |
| Client mod API (e.g. Watson) | ✔ | Limited |

## Quick start

1. Download the jar for your server software and put it into `plugins/` (Paper) or `mods/` (Fabric, together with the Fabric API).
2. Start the server once and give yourself permissions (`universalprotect.use`, `universalprotect.admin`).
3. Try it:

```text
/up inspect                              Click blocks to see their history
/up lookup u:Steve t:1d a:+block         What did Steve place in the last day?
/up rollback u:Steve t:1h r:20 #preview  Preview a rollback, nothing changes yet
/up apply                                Confirm the preview
/up stats                                Database size per log type
```

Details are in the [wiki](https://github.com/dev-muffin477/UniversalProtect/wiki): [Installation](https://github.com/dev-muffin477/UniversalProtect/wiki/Installation), [Commands](https://github.com/dev-muffin477/UniversalProtect/wiki/Commands), [Configuration](https://github.com/dev-muffin477/UniversalProtect/wiki/Configuration).

## Building from source

Requirements: JDK 25. Maven for the Paper edition. The Gradle wrapper for the Fabric edition is included.

```bash
./scripts/build-all.sh          # both editions, jars end up in dist/
```

Or one edition at a time:

```bash
cd paper  && mvn clean package   # paper/target/UniversalProtect.jar
cd fabric && ./gradlew build     # fabric/build/libs/
```

## Repository layout

```text
UniversalProtect/
├── paper/            Paper plugin (Maven)
│   ├── pom.xml
│   └── src/
├── fabric/           Fabric mod (Gradle, Fabric Loom)
│   ├── build.gradle
│   └── src/
├── scripts/          Helper scripts (build-all.sh)
├── .github/          CI workflow and issue templates
├── CHANGELOG.md
├── CONTRIBUTING.md
└── LICENSE
```

The documentation lives in the [wiki](https://github.com/dev-muffin477/UniversalProtect/wiki).

## Contributing

Bug reports and ideas are welcome, please use the [issue templates](https://github.com/dev-muffin477/UniversalProtect/issues/new/choose) and say which edition you use. See [CONTRIBUTING.md](CONTRIBUTING.md) for the code layout.

## License

GNU General Public License v3.0, see [LICENSE](LICENSE).

UniversalProtect is an independent project and is not affiliated with or endorsed by the CoreProtect authors. CoreProtect is a trademark of its respective owners.
