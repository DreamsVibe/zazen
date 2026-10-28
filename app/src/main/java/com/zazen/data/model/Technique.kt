package com.zazen.data.model

/**
 * What kind of sit this was. A vipassana sit always includes ānāpāna, so there is
 * no separate "both" option — tagging a sit as vipassana implies it.
 */
enum class Technique(val label: String, val shortLabel: String) {
    ANAPANA("Ānāpāna", "Ānāpāna"),
    VIPASSANA("Vipassana (incl. ānāpāna)", "Vipassana");

    companion object {
        fun fromName(name: String?): Technique? =
            if (name.isNullOrBlank()) null else entries.find { it.name == name }
    }
}
