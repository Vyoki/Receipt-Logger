package com.kitchenreceipts.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** One product usually bought from a supplier, with what an order would normally hold. */
data class OrderSuggestion(
    val productId: Long?,
    val name: String,
    /** kg and l for weights and volumes, otherwise the printed unit. */
    val unit: String,
    /** Average quantity per delivery that contained it. */
    val usualQuantity: BigDecimal,
    val lastQuantity: BigDecimal,
    val lastDate: LocalDate?,
    /** Deliveries in the window that contained it. */
    val times: Int,
    /** Average days between deliveries that contained it; null with only one. */
    val everyDays: Long?,
)

/**
 * The usual order to a supplier, from what was bought from them recently. It does not know the stock: the
 * operator changes the quantities before sending. Nothing is sent by the app; the list goes out through the
 * share sheet (WhatsApp, mail…).
 */
object Reorder {
    private val MC = MathContext.DECIMAL64
    const val WINDOW_DAYS = 56L

    data class Bought(
        val productId: Long?,
        val name: String,
        val documentId: Long,
        val date: LocalDate?,
        val quantity: BigDecimal?,
        val unit: String?,
    )

    fun suggestions(bought: List<Bought>, today: LocalDate, windowDays: Long = WINDOW_DAYS): List<OrderSuggestion> {
        val recent = bought.filter { b -> b.date != null && !b.date.isAfter(today) && ChronoUnit.DAYS.between(b.date, today) <= windowDays }
        data class Key(val id: String, val unit: String)
        val groups = LinkedHashMap<Key, MutableList<Pair<Bought, BigDecimal>>>()
        for (b in recent) {
            val q = b.quantity?.takeIf { it.signum() > 0 } ?: continue
            val u = Units.normalize(b.unit) ?: continue
            val f = Units.factorToBase(u)
            val (unit, qty) = if (f != null) Units.baseUnit(Units.dimension(u)!!) to q.multiply(f) else u to q
            val id = b.productId?.let { "p$it" } ?: ("d" + ProductMatching.aliasKey(b.name))
            groups.getOrPut(Key(id, unit)) { mutableListOf() } += b to qty
        }
        return groups.map { (k, rows) ->
            // Several lines of one delivery count as one delivery.
            val perDelivery = rows.groupBy { it.first.documentId }.map { (_, r) -> r.first().first.date!! to r.fold(BigDecimal.ZERO) { a, x -> a.add(x.second) } }
                .sortedBy { it.first }
            val usual = perDelivery.fold(BigDecimal.ZERO) { a, x -> a.add(x.second) }.divide(BigDecimal(perDelivery.size), MC)
            val dates = perDelivery.map { it.first }.distinct()
            val every = if (dates.size >= 2) ChronoUnit.DAYS.between(dates.first(), dates.last()) / (dates.size - 1) else null
            val last = perDelivery.last()
            val name = rows.maxByOrNull { it.first.date!! }!!.first.name
            OrderSuggestion(rows.first().first.productId, name, k.unit, round(usual, k.unit), last.second.stripTrailingZeros(), last.first, perDelivery.size, every)
        }.sortedWith(compareByDescending<OrderSuggestion> { it.times }.thenBy { it.name.lowercase() })
    }

    /** Pieces and packs in whole numbers; kg and l to half a unit under 10, whole units above. */
    fun round(q: BigDecimal, unit: String): BigDecimal = when {
        Units.dimension(unit) == null -> q.setScale(0, RoundingMode.HALF_UP).max(BigDecimal.ONE)
        q < BigDecimal.TEN -> q.multiply(BigDecimal(2)).setScale(0, RoundingMode.HALF_UP).divide(BigDecimal(2)).max(BigDecimal("0.5")).stripTrailingZeros()
        else -> q.setScale(0, RoundingMode.HALF_UP)
    }

    /** The order as a message: one line per product with a quantity. */
    fun message(heading: String, lines: List<Pair<String, String>>, footer: String?): String = buildString {
        append(heading).append('\n')
        lines.forEach { (name, qty) -> append("- ").append(name).append(": ").append(qty).append('\n') }
        footer?.takeIf { it.isNotBlank() }?.let { append('\n').append(it) }
    }.trimEnd()
}
