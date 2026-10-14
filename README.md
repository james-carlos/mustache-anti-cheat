# GuardAC

Lightweight anti-cheat for Paper 26.2 (built against paper-api 26.2.build.133-stable).

Checks: Fly, Speed, Timer, NoFall, Reach, KillAura (angle + multi-target), AutoClicker.

## Build

Requires JDK 25 and Maven.

    mvn clean package

The plugin jar is `target/GuardAC.jar`. Drop it into your server's `plugins/` folder and restart.

No Java/Maven installed? Push this folder to a GitHub repo - the workflow in
`.github/workflows/build.yml` builds the jar for you (Actions tab -> latest run -> Artifacts).

## Commands

- `/guardac reload` - reload config.yml
- `/guardac alerts` - toggle your own cheat alerts
- `/guardac vl <player>` - show a player's violation levels
- `/guardac clear <player>` - reset a player's violation levels

## Permissions

- `guardac.admin` - use /guardac (op)
- `guardac.alerts` - see alerts (op)
- `guardac.bypass` - never checked (nobody by default)

## Tuning

Everything is in `plugins/GuardAC/config.yml`. Start with the kick punishments removed (or thresholds
raised) and watch the alerts for a few days so you can see what your real players trigger, then
tighten. Raise `buffer` or loosen limits for any check that flags legit players.
