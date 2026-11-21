# Contributing

This is a hobby project for a few wall tablets, written mostly by an AI coding agent. Changes of every size are
welcome: fixes, new cards, support for another tablet.

## Working on it

1. Build: see [docs/BUILD.md](docs/BUILD.md). `./gradlew assembleRelease lintRelease` must pass with 0 lint errors.
2. Test on a **real old tablet** if you can (Android 5–7). Most bugs in this project only show up there: TLS,
   media, fonts, USB.
3. Keep the house rules below, then open a pull request with a screenshot (`adb exec-out screencap -p > shot.png`)
   for anything visual.

## House rules (for humans and agents)

- **No household data in the repo.** Entity ids, names, IPs, tokens and photos go in per-tablet settings or the
  gitignored `house/` profile, never in code, docs or commits.
- **Secrets never in git or chat logs.** Credentials reach a tablet only through the phone setup page.
- **Code-only UI**, no new heavy dependencies (no AndroidX/Compose/WebView dashboards). APK size and RAM matter on
  1 GB devices.
- **minSdk 21.** Guard newer APIs with `Build.VERSION.SDK_INT` checks.
- **Never block the main thread** with network or USB.
- **Third-party logos and art** don't go into the repo.
- Keep docs in sync. If you change behaviour a doc describes, update the doc in the same PR.

## Using an AI agent

The repo ships a skill for coding agents at `.claude/skills/goathouse-kiosk/SKILL.md` (Claude Code loads it
automatically; other agents can be pointed at it or at `AGENTS.md`). It explains the architecture, the traps and
how to test on hardware, so an agent can pick up where the last one stopped. Ask your agent to read it first.
