# Zazen+ 🧘

A fork of [Zazen](https://github.com/tupini07/zazen), the modern, minimal meditation
timer for Android, focused on session tracking and a warmer look. It installs as a
separate app (`com.zazen.plus`), so it can sit next to the original.

## Download

Get the latest APK from the [Releases page](https://github.com/DreamsVibe/zazen/releases/latest)
and open it on your phone. You may need to allow "Install unknown apps" first. Android
8.0 or newer is required.

To move your history over from the original Zazen, export a backup there (Stats →
download icon), then in Zazen+ go to Stats → ⋮ → Restore from backup.

## What Zazen+ adds

- **Start bell** — pick any bell, or none, to open a sit
- **Start delay** — an optional 5 s to 1 min countdown before the start bell
- **Counting past the bell** — when a timed sit ends, the clock keeps counting up the
  total time until you finish, and the full time is logged
- **Hold to finish** — Finish sit and Stop need a one-second hold, so a stray touch
  can't end a sit
- **Bells on time with the screen off** — the phone stays awake while a sit runs
- **Post-sit notes** — tag each sit Ānāpāna or Vipassana and add notes;
  all editable later
- **Stats** — streaks with grace days, a GitHub-style heatmap, a calendar, trends by
  time of day and over time, 38 milestones, and a full history (streaks and
  milestones can be hidden)
- **Export** — CSV of every sit, plus JSON backup and restore (old Zazen backups
  restore fine)
- **New look** — warm ink and saffron palette (paper and ochre in light mode) with
  serif clock digits

See [FORK_NOTES.md](FORK_NOTES.md) for the full details.

## Features from Zazen

- **Clean timer** — full-screen countdown with circular progress; after the
  closing bell, extra sitting time counts up until you stop and is saved with the session
- **Open-ended sits** — a count-up mode with no set duration; the closing bell
  rings when you decide you're done
- **Interval bells** — schedule bells at specific times within a session
  (e.g., bell at 5 min for body scan, different bell at 15 min for breath work)
- **Repeating bells** — ring at a fixed interval, in both timed and open sits
- **Silent mode** — vibrate-only option so you can meditate near sleeping kids
- **Session tracking** — view your meditation history and total time
- **Presets** — save a setup by name, then overwrite the loaded preset or save a new copy
- **Beautiful sounds** — Tibetan bowls, bells, and gongs

## Building

Requires [mise](https://mise.jdx.dev/) for toolchain management:

```bash
mise install          # installs JDK 17 + Android SDK
./gradlew assembleDebug
```

Or open the folder in Android Studio and press **Run**. The APK lands in
`app/build/outputs/apk/debug/app-debug.apk`.

For a smaller, optimised build, run `./gradlew assembleRelease`; the APK lands in
`app/build/outputs/apk/release/app-release.apk`.

## Sound Credits

Sound files sourced from the [BodhiTimer](https://github.com/yuttadhammo/BodhiTimer)
project and [nyxkn/meditation](https://github.com/nyxkn/meditation).
See [NOTICES](NOTICES) for individual attributions.

## License

MIT — see [LICENSE](LICENSE) for details.
