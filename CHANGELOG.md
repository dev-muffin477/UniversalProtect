# Changelog

All notable changes. Older versions and more detail are on the [wiki](https://github.com/dev-muffin477/UniversalProtect/wiki/Changelog). Entries apply to both editions unless marked **Paper** or **Fabric**.

## 2.1.2

### Fixed

- **Anti-duplication did not work with the default config:** picked-up items were only logged when `log.pickup` was on, which is off by default, so a rollback of a broken container found nothing to take back. Pickups are now logged (as inventory entries) whenever `log.inventory` and `rollback.reconcile-containers` are on, no matter what `log.pickup` says. Only containers broken after this update benefit from the pickup log.
- New `rollback.reconcile-breaker` (default `true`): items of the container that no logged pickup explains are taken from the player who broke it (inventory, armor, offhand), if he is online. This also works for breaks that were logged before this update.

## 2.1.1

### Fixed

- **Item duplication when rolling back a broken container:** a rollback of a broken chest (or any container) brought back its contents, while the items that had dropped on the ground, been picked up or moved elsewhere stayed in the world. Now the rollback cleans that up, for up to one day after the break (`rollback.reconcile-containers`, `rollback.reconcile-radius`, `rollback.reconcile-window-minutes`):
  - dropped items near the container are removed,
  - items that players picked up are taken from their inventory, armor and offhand slots,
  - items that those players put into other containers are taken from there,
  - players who are offline lose the items when they next join (`pending-reclaim.txt`, kept for 14 days).
- Rolling back a container together with the items that were put into it now empties the container first. Before, the container was removed first and its contents fell to the ground.
- The rollback report has a new line **Duplicates prevented**. `/up undo` gives items back to online players and containers, but cannot cancel the entries waiting for offline players.
- **Fabric:** containers are recognised by their block type, no longer by the saved block data.

## 2.1.0

Paper and Fabric are now developed in one repository.

### Added

- **Storage statistics:** `/up stats` shows the space used by every log type (blocks, containers, items, entities, chat, players, interactions) with the number of rows. With DuckDB and SQLite the sizes add up to the real file, with MySQL and ClickHouse they are estimates.
- **`/up clear <category> [t:<time>]`:** delete a whole log type or only its old entries, with a confirmation step (`/up clear confirm`). The database is compacted afterwards. The staff audit log is protected.
- **`log.creative`:** items taken from the creative inventory can be switched on or off, also per world.
- **Discord notifications:** alerts and staff actions in separate webhooks, an alert level filter (`discord.min-level`) and a list of staff commands to report (`discord.audit-events`). Startup errors are reported too.
- **Paper:** anonymous usage statistics (bStats), can be turned off with `metrics.enabled: false`. The Fabric edition sends none.

### Changed

- Creative inventory logging records the amount that was really added. Before, nothing was logged when the player already owned one item of that kind. `create` entries carry `creative` in their meta field.
- `clear` was added to the possible values of `discord.audit-events`. New `config.yml` files include it, existing files need it added by hand.
- Discord alerts start at level MEDIUM by default (`discord.min-level: 2`).

## 2.0.0

- Native support for Paper and Fabric from one codebase.
- Databases: DuckDB (default), SQLite, MySQL, ClickHouse, with live migration (`/up migrate-db`).
- Rollback and restore for containers, inventories, entity kills and spawns, and command rollback (`#command`).
- Incident investigation, player timeline, cause tracking, alerts, staff audit log, CoreProtect import and database compaction.
