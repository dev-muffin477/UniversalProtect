# Contributing

Thanks for helping. Bug reports, ideas and pull requests are welcome.

## Reporting a problem

Open an issue with the template and include:

- the edition (Paper or Fabric), Minecraft version and mod/plugin version (`/up status`)
- for Fabric: Fabric Loader and Fabric API version
- the database engine and the relevant console excerpt

## Layout

| Folder | Content |
| :--- | :--- |
| `paper/` | Paper plugin, built with Maven (`mvn clean package`) |
| `fabric/` | Fabric mod, built with Gradle (`./gradlew build`) |
| `scripts/` | Helper scripts, for example `build-all.sh` |

Both editions use the package `de.muffin.universalprotect`. Many classes have the same name and purpose in both folders (for example `DatabaseManager`, `CommandHandler`, `Settings`, `DbType`). Platform code differs: the Paper edition uses Bukkit events (`ActionLogger`), the Fabric edition uses events and mixins (`Hooks`, `mixin/`).

## Guidelines

- A change to a shared feature (commands, database, logging options) belongs into **both** folders. Mention it in the pull request if an edition is intentionally left out.
- Keep existing functions working. Add new options with a default so that old `config.yml` files keep working.
- User-visible changes go into `CHANGELOG.md`, and into the [wiki](https://github.com/dev-muffin477/UniversalProtect/wiki) when they add a command, option or behaviour.
- Build both editions before you open a pull request: `./scripts/build-all.sh`.
- Requirements: JDK 25.

## License

By contributing you agree that your contribution is licensed under the GNU General Public License v3.0, see `LICENSE`.
