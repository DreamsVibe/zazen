# Zazen+ (fork of tupini07/zazen)

A fork of [Zazen](https://github.com/tupini07/zazen) (MIT) focused on session tracking
and a warmer look. It installs as a separate app, **Zazen+** (`com.zazen.plus`), so it
can sit next to the original.

## What's new

**Timer**
- **Start bell**: pick any bell, or none, on the setup screen.
- **Start delay**: an optional countdown (5 s, 10 s, 20 s, 30 s or 1 min) before the
  start bell rings, so you can settle in first. It isn't counted as sitting time.
- **Counting past the bell**: when a timed sit reaches its end, the end bell rings and
  the clock turns into a stopwatch showing the total time, counting up from the planned
  length. Hold *Finish sit* when you're done; the whole time is logged, not just the plan.
- **Hold to finish**: Finish sit and Stop need a one-second hold, so a stray touch can't
  end a sit.
- **Bells on time with the screen off**: the timer keeps the processor awake while a sit
  runs (released on pause and finish, with a safety timeout).
- New look: warm ink and saffron palette (or paper and ochre in light mode), serif clock
  digits, a glowing progress ring, and an orbiting dot during open sits or after the end bell.

**After each sit**
- Tag it **Ānāpāna** or **Vipassana**. Vipassana implies ānāpāna, so there's no
  "both" option.
- Add notes. All of this can be edited later from History or Calendar.

**Stats** (the chart icon on the setup screen), in five tabs:
- **Overview**: streak with **grace days** (0–3 missed days in a row allowed),
  best streak, totals for today, this week, this month and all time, a GitHub-style
  **heatmap**, plus average sit and longest sit.
- **Calendar**: month grid shaded by time sat. Tap a day to see its sits.
- **Trends**: time of day (minutes per 3-hour block), total time,
  and average sit length over 12 weeks, 6 months, 1 year or all time.
- **Milestones**: 38 milestones across Easy, Medium, Hard and Legendary. They cover
  total hours, sit counts, streaks, long sits, twice-a-day practice, two-hour days,
  early sits and vipassana sits.
- **History**: grouped by day, with exact start–end times, minutes, tags, stars and
  notes. Long-press to multi-select and copy.

Streaks and milestones can be hidden from the ⋮ menu (**Hide streaks & milestones**).
This only hides them: every sit is still logged and counted.

**Export** (⋮ menu in Stats)
- **CSV** of every sit: date, weekday, start and end time, planned and actual minutes,
  type, technique and notes.
- **JSON** full backup and restore. Old Zazen backups restore fine; new fields get
  default values.

## Building

The source is unchanged in toolchain: AGP 8.7, Kotlin 2.0.21, compileSdk 34, JDK 17.

1. Open this folder in **Android Studio** (File → Open). Let Gradle sync.
2. Plug in your phone with USB debugging on and press **Run**.
   You can also use **Build → Build App Bundle(s)/APK(s) → Build APK(s)**, then copy
   `app/build/outputs/apk/debug/app-debug.apk` to the phone and open it.

From a terminal, the equivalent is `./gradlew assembleDebug`.

**Moving your history over from the original Zazen:** in Zazen, go to Stats and tap
the download icon to export a backup. In Zazen+, go to Stats → ⋮ → Restore from backup.

## Changed files

- `data/model/` gains `Technique.kt`. `MeditationSession` gets technique
  and extended, and `TimerState` gets `SitPhase`.
- `data/stats/StatsCalculator.kt` is a pure-Kotlin stats engine for streaks, trends
  and milestones.
- `data/repository/SessionCsv.kt` handles CSV export. `BackupRepository` and
  `PreferencesRepository` gain the new fields.
- `di/DatabaseModule.kt` adds Room migration 5 → 6, which adds three columns.
- `service/TimerService.kt` handles the start bell, start delay and counting past the end bell.
- `ui/screens/timer/TimerScreen.kt` has the new timer and review screen.
- `ui/screens/stats/*` are the new stats tabs.
- `ui/theme/*` holds the new palette and type. The launcher icon is recoloured.
