package com.cratez.core.music

import kotlin.math.abs
import kotlin.math.min

/** Orders tracks like a DJ would: each next track in a compatible key, close in tempo, energy building. */
object MixOrder {
    data class Item(val id: Long, val bpm: Double, val key: Int, val energy: Int)

    private fun tempoGap(a: Double, b: Double): Double {
        if (a <= 0 || b <= 0) return 0.08
        return listOf(b, b * 2, b / 2).minOf { abs(it - a) / a }
    }

    private fun score(from: Item, to: Item): Double {
        val keyScore = if (from.key < 0 || to.key < 0) 0.5 else when (MusicKey.compatibility(from.key, to.key)) {
            MusicKey.Compat.PERFECT -> 3.0
            MusicKey.Compat.GREAT -> 2.6
            MusicKey.Compat.ENERGY -> 1.6
            MusicKey.Compat.NONE -> 0.0
        }
        val tempo = -min(tempoGap(from.bpm, to.bpm), 0.3) * 25
        val energyStep = to.energy - from.energy
        val energy = when { energyStep in 0..1 -> 0.6; energyStep == 2 -> 0.2; energyStep < 0 -> energyStep * 0.25; else -> -0.4 }
        return keyScore + tempo + energy
    }

    /** Greedy chain starting from [startId] (or the lowest-energy, slowest track). */
    fun order(items: List<Item>, startId: Long? = null): List<Long> {
        if (items.size <= 2) return items.map { it.id }
        val left = items.toMutableList()
        var cur = left.firstOrNull { it.id == startId } ?: left.minWith(compareBy<Item> { it.energy }.thenBy { it.bpm })
        left.remove(cur)
        val out = mutableListOf(cur.id)
        while (left.isNotEmpty()) {
            val next = left.maxBy { score(cur, it) }
            out += next.id; left.remove(next); cur = next
        }
        return out
    }
}
