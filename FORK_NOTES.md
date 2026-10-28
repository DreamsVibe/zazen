# Zazen+ (fork of tupini07/zazen)

A fork of [Zazen](https://github.com/tupini07/zazen) (MIT) focused on session tracking
and a warmer look. It installs as a separate app, **Zazen+** (`com.zazen.plus`), so it
can sit next to the original.

## What's new

**Timer**
- **Start bell**: pick any bell, or none, on the setup screen.
- **Keep sitting**: when a timed sit reaches its end, the end bell rings and a big
  round *Keep sitting* button appears. Tap it to extend the sit indefinitely, then tap
  *Finish sit* to ring the closing bell and log the whole time. Tap *Finish here*, or
  leave it for 2 minutes, to log the planned length only. Both options are also in the
  notification.
- New look: warm ink and saffron palette (or paper and ochre in light mode), serif clock
  digits, a glowing progress ring, and an orbiting dot during open or extended sits.

**After each sit**
- Rate it 1–5 (Restless → Deep).
- Tag it **Ānāpāna** or **Vipassana**. Vipassana implies ānāpāna, so there's no
  "both" option.
- Add notes. All of this can be edited later from History or Calendar.

**Stats** (the chart icon on the setup screen), in five tabs:
- **Overview**: streak with **grace days** (0–3 missed days in a row allowed),
  best streak, totals for today, this week, this month and all time, a GitHub-style
  **heatmap**, plus average sit, longest sit and average rating.
- **Calendar**: month grid shaded by time sat. Tap a day to see its sits.
- **Trends**: time of day (minutes and average rating per 3-hour block), total time,
  average sit length and average rating over 12 weeks, 6 months, 1 year or all time.
- **Milestones**: 38 milestones across Easy, Medium, Hard and Legendary. They cover
  total hours, sit counts, streaks, long sits, twice-a-day practice, two-hour days,
  early sits and vipassana sits.
- **History**: grouped by day, with exact start–end times, minutes, tags, stars and
  notes. Long-press to multi-select and copy.

**Export** (⋮ menu in Stats)
- **CSV** of every sit: date, weekday, start and end time, planned and actual minutes,
  type, technique, rating and notes.
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

- `data/model/` gains `Technique.kt`. `MeditationSession` gets rating, technique
  and extended, and `TimerState` gets `SitPhase`.
- `data/stats/StatsCalculator.kt` is a pure-Kotlin stats engine for streaks, trends
  and milestones.
- `data/repository/SessionCsv.kt` handles CSV export. `BackupRepository` and
  `PreferencesRepository` gain the new fields.
- `di/DatabaseModule.kt` adds Room migration 5 → 6, which adds three columns.
- `service/TimerService.kt` handles the start bell and the keep-sitting phases.
- `ui/screens/timer/TimerScreen.kt` has the new timer and review screen.
- `ui/screens/stats/*` are the new stats tabs.
- `ui/theme/*` holds the new palette and type. The launcher icon is recoloured.
