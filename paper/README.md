# UniversalProtect, Paper edition

The Paper plugin. See the [main README](../README.md) and the [wiki](https://github.com/dev-muffin477/UniversalProtect/wiki) for features and usage.

| | |
| :--- | :--- |
| Server | [Paper](https://papermc.io/) for Minecraft 26.3 |
| Java | 25 |
| Build | Maven |
| Install | `plugins/UniversalProtect.jar`, config in `plugins/UniversalProtect/` |

```bash
mvn clean package        # result: target/UniversalProtect.jar
```

The database drivers (DuckDB, ClickHouse, MySQL) are downloaded by Paper on the first start, SQLite ships with Paper. World names are Bukkit names such as `world` and `world_nether`.

Code: `src/main/java/de/muffin/universalprotect/`. Server events are handled in `ActionLogger.java`, the inspector in `InspectorListener.java`.
