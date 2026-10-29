package com.zazen.data.stats

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Plain-Kotlin stats engine (no Android dependencies) so it can be unit tested
 * on the JVM. Everything is derived from the session log on demand.
 */

/** The parts of a logged sit the stats need. */
data class SitRecord(
    val id: Long,
    val startMillis: Long,
    val sitMillis: Long,
    /** Technique name or "". */
    val technique: String = "",
)

data class Totals(val sits: Int, val millis: Long) {
    val minutes: Long get() = millis / 60_000
}

data class StreakInfo(
    /** Calendar days covered by the current streak (grace days included). */
    val current: Int,
    val best: Int,
    /** True if you've sat today. */
    val satToday: Boolean,
    /** Streak still alive, but today needs a sit to keep it going after grace runs out. */
    val alive: Boolean,
    /** Missed days in a row right now (not counting today). */
    val missedInARow: Int,
    val graceDays: Int,
    /** Grace days spent inside the current streak. */
    val graceUsedInCurrent: Int,
) {
    /** How many more days you can miss before the streak breaks (today counts). */
    val graceLeft: Int get() = (graceDays - missedInARow).coerceAtLeast(0)
}

data class TimeBucket(
    val label: String,
    val startHour: Int,
    val sits: Int,
    val millis: Long,
    val avgMillis: Long,
)

/** One week or month on a trend chart. */
data class PeriodPoint(
    val start: LocalDate,
    val sits: Int,
    val millis: Long,
    val avgMillis: Long,
)

enum class Tier(val label: String) { EASY("Easy"), MEDIUM("Medium"), HARD("Hard"), LEGENDARY("Legendary") }

data class Milestone(
    val id: String,
    val category: String,
    val title: String,
    val description: String,
    val tier: Tier,
    val target: Long,
    val progress: Long,
    val achievedOn: LocalDate?,
) {
    val achieved: Boolean get() = achievedOn != null
    val fraction: Float get() = if (target <= 0) 1f else (progress.toFloat() / target).coerceIn(0f, 1f)
}

data class StatsSnapshot(
    val today: Totals,
    val week: Totals,
    val month: Totals,
    val all: Totals,
    val streak: StreakInfo,
    /** Millis sat per day, every day that has a sit. */
    val dayMillis: Map<LocalDate, Long>,
    val daySits: Map<LocalDate, Int>,
    val timeOfDay: List<TimeBucket>,
    val weeks: List<PeriodPoint>,
    val months: List<PeriodPoint>,
    val avgSitMillis: Long,
    val longestSitMillis: Long,
    val milestones: List<Milestone>,
    val weekStart: DayOfWeek,
) {
    companion object {
        val EMPTY = StatsCalculator.compute(emptyList(), LocalDate.now(), ZoneId.systemDefault(), 1)
    }
}

object StatsCalculator {

    fun compute(
        sits: List<SitRecord>,
        today: LocalDate,
        zone: ZoneId,
        graceDays: Int,
        weekStart: DayOfWeek = DayOfWeek.MONDAY,
    ): StatsSnapshot {
        val sorted = sits.sortedBy { it.startMillis }
        fun dateOf(s: SitRecord): LocalDate = Instant.ofEpochMilli(s.startMillis).atZone(zone).toLocalDate()
        fun hourOf(s: SitRecord): Int = Instant.ofEpochMilli(s.startMillis).atZone(zone).hour

        val dayMillis = LinkedHashMap<LocalDate, Long>()
        val daySits = LinkedHashMap<LocalDate, Int>()
        for (s in sorted) {
            val d = dateOf(s)
            dayMillis[d] = (dayMillis[d] ?: 0L) + s.sitMillis
            daySits[d] = (daySits[d] ?: 0) + 1
        }

        val thisWeekStart = today.with(TemporalAdjusters.previousOrSame(weekStart))
        val thisMonthStart = today.withDayOfMonth(1)
        fun totalsSince(from: LocalDate): Totals {
            val inRange = sorted.filter { val d = dateOf(it); !d.isBefore(from) && !d.isAfter(today) }
            return Totals(inRange.size, inRange.sumOf { it.sitMillis })
        }

        return StatsSnapshot(
            today = totalsSince(today),
            week = totalsSince(thisWeekStart),
            month = totalsSince(thisMonthStart),
            all = Totals(sorted.size, sorted.sumOf { it.sitMillis }),
            streak = streak(daySits.keys.toList(), today, graceDays),
            dayMillis = dayMillis,
            daySits = daySits,
            timeOfDay = timeOfDay(sorted, ::hourOf),
            weeks = periods(sorted, today, ::dateOf) { it.with(TemporalAdjusters.previousOrSame(weekStart)) }
                .let { fillWeeks(it, sorted.firstOrNull()?.let(::dateOf), today, weekStart) },
            months = periods(sorted, today, ::dateOf) { it.withDayOfMonth(1) }
                .let { fillMonths(it, sorted.firstOrNull()?.let(::dateOf), today) },
            avgSitMillis = if (sorted.isEmpty()) 0 else sorted.sumOf { it.sitMillis } / sorted.size,
            longestSitMillis = sorted.maxOfOrNull { it.sitMillis } ?: 0L,
            milestones = milestones(sorted, ::dateOf, ::hourOf, graceDays),
            weekStart = weekStart,
        )
    }

    // --- Streaks ---

    /** [days] must be sorted ascending and distinct. */
    fun streak(days: List<LocalDate>, today: LocalDate, graceDays: Int): StreakInfo {
        val grace = graceDays.coerceAtLeast(0)
        if (days.isEmpty()) {
            return StreakInfo(0, 0, satToday = false, alive = false, missedInARow = 0, graceDays = grace, graceUsedInCurrent = 0)
        }
        val chains = chains(days, grace)
        val best = chains.maxOf { spanDays(it) }
        val last = chains.last()
        val lastDay = last.last()
        val daysSinceLast = ChronoUnit.DAYS.between(lastDay, today).toInt()
        val satToday = daysSinceLast == 0
        // Alive when the gap so far (excluding today) is still within grace.
        val missed = (daysSinceLast - 1).coerceAtLeast(0)
        val alive = daysSinceLast >= 0 && missed <= grace
        val current = if (alive) spanDays(last) else 0
        val used = if (alive) spanDays(last) - last.size else 0
        return StreakInfo(
            current = current,
            best = best,
            satToday = satToday,
            alive = alive,
            missedInARow = if (alive) missed else 0,
            graceDays = grace,
            graceUsedInCurrent = used,
        )
    }

    private fun chains(days: List<LocalDate>, grace: Int): List<List<LocalDate>> {
        val out = mutableListOf<MutableList<LocalDate>>()
        for (d in days) {
            val cur = out.lastOrNull()
            if (cur != null && ChronoUnit.DAYS.between(cur.last(), d) - 1 <= grace) cur.add(d)
            else out.add(mutableListOf(d))
        }
        return out
    }

    private fun spanDays(chain: List<LocalDate>): Int =
        ChronoUnit.DAYS.between(chain.first(), chain.last()).toInt() + 1

    // --- Time of day ---

    private val BUCKET_LABELS = listOf("12–3a", "3–6a", "6–9a", "9a–12p", "12–3p", "3–6p", "6–9p", "9p–12a")

    private fun timeOfDay(sits: List<SitRecord>, hourOf: (SitRecord) -> Int): List<TimeBucket> =
        (0 until 8).map { b ->
            val inBucket = sits.filter { hourOf(it) / 3 == b }
            TimeBucket(
                label = BUCKET_LABELS[b],
                startHour = b * 3,
                sits = inBucket.size,
                millis = inBucket.sumOf { it.sitMillis },
                avgMillis = if (inBucket.isEmpty()) 0 else inBucket.sumOf { it.sitMillis } / inBucket.size,
            )
        }

    // --- Trend periods ---

    private fun periods(
        sits: List<SitRecord>,
        today: LocalDate,
        dateOf: (SitRecord) -> LocalDate,
        periodStart: (LocalDate) -> LocalDate,
    ): Map<LocalDate, PeriodPoint> =
        sits.filter { !dateOf(it).isAfter(today) }
            .groupBy { periodStart(dateOf(it)) }
            .mapValues { (start, list) ->
                PeriodPoint(
                    start = start,
                    sits = list.size,
                    millis = list.sumOf { it.sitMillis },
                    avgMillis = list.sumOf { it.sitMillis } / list.size,
                )
            }

    private fun empty(start: LocalDate) = PeriodPoint(start, 0, 0, 0)

    /** Every week from the first sit to this week, including empty ones. */
    private fun fillWeeks(
        byStart: Map<LocalDate, PeriodPoint>,
        first: LocalDate?,
        today: LocalDate,
        weekStart: DayOfWeek,
    ): List<PeriodPoint> {
        if (first == null) return emptyList()
        val out = mutableListOf<PeriodPoint>()
        var w = first.with(TemporalAdjusters.previousOrSame(weekStart))
        val end = today.with(TemporalAdjusters.previousOrSame(weekStart))
        while (!w.isAfter(end)) {
            out.add(byStart[w] ?: empty(w))
            w = w.plusWeeks(1)
        }
        return out
    }

    private fun fillMonths(byStart: Map<LocalDate, PeriodPoint>, first: LocalDate?, today: LocalDate): List<PeriodPoint> {
        if (first == null) return emptyList()
        val out = mutableListOf<PeriodPoint>()
        var m = YearMonth.from(first)
        val end = YearMonth.from(today)
        while (!m.isAfter(end)) {
            val start = m.atDay(1)
            out.add(byStart[start] ?: empty(start))
            m = m.plusMonths(1)
        }
        return out
    }

    // --- Milestones ---

    private fun milestones(
        sits: List<SitRecord>,
        dateOf: (SitRecord) -> LocalDate,
        hourOf: (SitRecord) -> Int,
        graceDays: Int,
    ): List<Milestone> {
        // Running counters, recording the date each threshold was first crossed.
        val reached = HashMap<String, LocalDate>()
        fun mark(key: String, date: LocalDate) { if (key !in reached) reached[key] = date }

        val hourLadder = listOf(
            1L to Tier.EASY, 10L to Tier.EASY, 50L to Tier.MEDIUM, 100L to Tier.HARD,
            250L to Tier.HARD, 500L to Tier.LEGENDARY, 1000L to Tier.LEGENDARY,
        )
        val sitLadder = listOf(
            1L to Tier.EASY, 10L to Tier.EASY, 50L to Tier.MEDIUM, 100L to Tier.MEDIUM,
            365L to Tier.HARD, 1000L to Tier.LEGENDARY,
        )
        val streakLadder = listOf(
            3L to Tier.EASY, 7L to Tier.EASY, 21L to Tier.MEDIUM, 30L to Tier.MEDIUM,
            100L to Tier.HARD, 365L to Tier.LEGENDARY,
        )
        val longSitLadder = listOf(
            20L to Tier.EASY, 45L to Tier.MEDIUM, 60L to Tier.MEDIUM,
            90L to Tier.HARD, 120L to Tier.HARD, 180L to Tier.LEGENDARY,
        )
        val twiceLadder = listOf(1L to Tier.EASY, 7L to Tier.MEDIUM, 30L to Tier.HARD, 100L to Tier.LEGENDARY)
        val fullDayLadder = listOf(1L to Tier.MEDIUM, 10L to Tier.HARD, 50L to Tier.LEGENDARY)
        val earlyLadder = listOf(1L to Tier.EASY, 10L to Tier.MEDIUM, 50L to Tier.HARD)
        val vipassanaLadder = listOf(10L to Tier.EASY, 100L to Tier.MEDIUM, 500L to Tier.HARD)

        var totalMinutes = 0L
        var count = 0L
        var longestMinutes = 0L
        var early = 0L
        var vipassana = 0L
        val perDaySits = HashMap<LocalDate, Int>()
        val perDayMillis = HashMap<LocalDate, Long>()
        var twiceDays = 0L
        var fullDays = 0L

        for (s in sits) {
            val d = dateOf(s)
            count++
            totalMinutes += s.sitMillis / 60_000
            longestMinutes = maxOf(longestMinutes, s.sitMillis / 60_000)
            if (hourOf(s) in 3..5) early++
            if (s.technique == "VIPASSANA") vipassana++

            val before = perDaySits[d] ?: 0
            perDaySits[d] = before + 1
            if (before + 1 == 2) twiceDays++
            val beforeMillis = perDayMillis[d] ?: 0L
            perDayMillis[d] = beforeMillis + s.sitMillis
            if (beforeMillis < 120 * 60_000L && beforeMillis + s.sitMillis >= 120 * 60_000L) fullDays++

            hourLadder.forEach { (h, _) -> if (totalMinutes >= h * 60) mark("hours_$h", d) }
            sitLadder.forEach { (n, _) -> if (count >= n) mark("sits_$n", d) }
            longSitLadder.forEach { (m, _) -> if (s.sitMillis >= m * 60_000) mark("long_$m", d) }
            twiceLadder.forEach { (n, _) -> if (twiceDays >= n) mark("twice_$n", d) }
            fullDayLadder.forEach { (n, _) -> if (fullDays >= n) mark("fullday_$n", d) }
            earlyLadder.forEach { (n, _) -> if (early >= n) mark("early_$n", d) }
            vipassanaLadder.forEach { (n, _) -> if (vipassana >= n) mark("vipassana_$n", d) }
        }

        // Streak milestones: walk the sit days and track each chain's running span.
        val days = sits.map(dateOf).distinct().sorted()
        var bestStreak = 0L
        var chainStart: LocalDate? = null
        var prev: LocalDate? = null
        for (d in days) {
            if (prev == null || ChronoUnit.DAYS.between(prev, d) - 1 > graceDays) chainStart = d
            val span = ChronoUnit.DAYS.between(chainStart, d) + 1
            bestStreak = maxOf(bestStreak, span)
            streakLadder.forEach { (n, _) -> if (span >= n) mark("streak_$n", d) }
            prev = d
        }

        val out = mutableListOf<Milestone>()
        fun add(prefix: String, category: String, ladder: List<Pair<Long, Tier>>, progress: Long,
                title: (Long) -> String, desc: (Long) -> String) {
            ladder.forEach { (n, tier) ->
                out.add(Milestone("${prefix}_$n", category, title(n), desc(n), tier, n, progress, reached["${prefix}_$n"]))
            }
        }

        add("sits", "Sits", sitLadder, count,
            { n -> if (n == 1L) "First sit" else "$n sits" },
            { n -> if (n == 1L) "Log your first sit" else "Log $n sits in total" })
        add("hours", "Total time", hourLadder, totalMinutes / 60,
            { n -> if (n == 100L) "A course's worth" else "$n hour" + if (n == 1L) "" else "s" },
            { n -> if (n == 100L) "100 hours — about what a 10-day course holds" else "Sit $n hour${if (n == 1L) "" else "s"} in total" })
        add("streak", "Streaks", streakLadder, bestStreak,
            { n -> "$n-day streak" },
            { n -> "Keep a streak going for $n days" })
        add("long", "Long sits", longSitLadder, longestMinutes,
            { n -> if (n == 60L) "Strong hour" else if (n >= 60) "${n / 60.0}".removeSuffix(".0") + "-hour sit" else "$n-minute sit" },
            { n -> if (n == 60L) "One unbroken hour, adhiṭṭhāna length" else "A single sit of at least ${formatMinutes(n)}" })
        add("twice", "Practice", twiceLadder, twiceDays,
            { n -> if (n == 1L) "Morning & evening" else "Twice daily ×$n" },
            { n -> if (n == 1L) "Sit twice in one day" else "Sit twice a day on $n days" })
        add("fullday", "Practice", fullDayLadder, fullDays,
            { n -> if (n == 1L) "Two-hour day" else "Two-hour days ×$n" },
            { n -> if (n == 1L) "Sit 2 hours in one day" else "Sit 2+ hours a day on $n days" })
        add("early", "Practice", earlyLadder, early,
            { n -> if (n == 1L) "Early riser" else "Early riser ×$n" },
            { n -> "Start ${if (n == 1L) "a sit" else "$n sits"} between 3 and 6 am" })
        add("vipassana", "Technique", vipassanaLadder, vipassana,
            { n -> "$n vipassana sits" },
            { n -> "Tag $n sits as vipassana" })
        return out
    }

    private fun formatMinutes(n: Long): String =
        if (n >= 60 && n % 60 == 0L) "${n / 60} hour${if (n == 60L) "" else "s"}" else "$n minutes"
}
