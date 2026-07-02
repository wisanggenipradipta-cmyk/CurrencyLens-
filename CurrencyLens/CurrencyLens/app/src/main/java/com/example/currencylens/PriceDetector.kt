package com.example.currencylens

import java.util.regex.Pattern

/**
 * Detects currency amounts inside a line of OCR text.
 *
 * Handles:
 *  - Symbol prefixes/suffixes:  Rp 25.000 | $12.99 | €5,50 | 120฿ | RM18 | 25.000 IDR
 *  - Ambiguous separators:      25.000 (ID thousands) vs 12.99 (decimal) vs 1.299,50 (EU)
 *  - Shorthand multipliers:     25K / 25rb (common on Indonesian menus) = 25,000
 *  - Bare numbers when the user has manually fixed the source currency
 */
object PriceDetector {

    data class PriceMatch(
        val start: Int,          // char offset in the line
        val end: Int,
        val currency: String,    // ISO code, e.g. "IDR"
        val amount: Double
    )

    // Longest tokens first so "US$" wins over "$", "Rp" over nothing, etc.
    private val tokenToCode = listOf(
        "US$" to "USD", "S$" to "SGD", "A$" to "AUD", "HK$" to "HKD", "NT$" to "TWD",
        "Rp" to "IDR", "RP" to "IDR", "rp" to "IDR",
        "RM" to "MYR",
        "IDR" to "IDR", "USD" to "USD", "EUR" to "EUR", "GBP" to "GBP", "JPY" to "JPY",
        "SGD" to "SGD", "MYR" to "MYR", "THB" to "THB", "VND" to "VND", "PHP" to "PHP",
        "CNY" to "CNY", "RMB" to "CNY", "KRW" to "KRW", "INR" to "INR", "AUD" to "AUD",
        "AED" to "AED", "CHF" to "CHF", "HKD" to "HKD", "TWD" to "TWD", "SAR" to "SAR",
        "$" to "USD", "€" to "EUR", "£" to "GBP", "¥" to "JPY",
        "฿" to "THB", "₫" to "VND", "₹" to "INR", "₩" to "KRW", "₱" to "PHP"
    )

    private val symbolAlternation: String = tokenToCode
        .map { Pattern.quote(it.first) }
        .joinToString("|")

    // 1.234.567 | 1,234,567 | 12 500 | 12.99 | 5,50 | 25000
    private const val NUM = """\d{1,3}(?:[.,\u00A0 ]\d{3})+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?"""
    private const val MULT = """(?:[kK]|rb|RB|ribu)?"""

    private val prefixPattern: Pattern =
        Pattern.compile("""($symbolAlternation)\s?($NUM)\s?($MULT)""")

    private val suffixPattern: Pattern =
        Pattern.compile("""($NUM)\s?($MULT)\s?($symbolAlternation)""")

    private val barePattern: Pattern =
        Pattern.compile("""(?<![\w.,])($NUM)\s?($MULT)(?![\w.,])""")

    /**
     * @param sourceOverride ISO code if the user pinned the source currency, or null for auto.
     *        When pinned, bare numbers ("35.000") are also treated as prices.
     */
    fun findPrices(line: String, sourceOverride: String?): List<PriceMatch> {
        val results = mutableListOf<PriceMatch>()
        val claimed = mutableListOf<IntRange>()

        fun overlaps(s: Int, e: Int) = claimed.any { s < it.last + 1 && e > it.first }

        // 1. Symbol/code before the number
        val pm = prefixPattern.matcher(line)
        while (pm.find()) {
            val code = codeFor(pm.group(1) ?: continue) ?: continue
            val amount = parseNumber(pm.group(2) ?: continue, pm.group(3)) ?: continue
            results += PriceMatch(pm.start(), pm.end(), sourceOverride ?: code, amount)
            claimed += pm.start()..pm.end()
        }

        // 2. Symbol/code after the number
        val sm = suffixPattern.matcher(line)
        while (sm.find()) {
            if (overlaps(sm.start(), sm.end())) continue
            val code = codeFor(sm.group(3) ?: continue) ?: continue
            val amount = parseNumber(sm.group(1) ?: continue, sm.group(2)) ?: continue
            results += PriceMatch(sm.start(), sm.end(), sourceOverride ?: code, amount)
            claimed += sm.start()..sm.end()
        }

        // 3. Bare numbers — only when the user pinned a source currency
        if (sourceOverride != null) {
            val bm = barePattern.matcher(line)
            while (bm.find()) {
                if (overlaps(bm.start(), bm.end())) continue
                val raw = bm.group(1) ?: continue
                val mult = bm.group(2)
                val amount = parseNumber(raw, mult) ?: continue
                // Filter obvious non-prices: tiny bare integers with no grouping/decimal/multiplier
                val looksGrouped = raw.any { it == '.' || it == ',' || it == ' ' || it == '\u00A0' }
                val hasMult = !mult.isNullOrEmpty()
                if (!looksGrouped && !hasMult && amount < 100) continue
                results += PriceMatch(bm.start(), bm.end(), sourceOverride, amount)
                claimed += bm.start()..bm.end()
            }
        }

        return results.sortedBy { it.start }
    }

    private fun codeFor(token: String): String? =
        tokenToCode.firstOrNull { it.first.equals(token, ignoreCase = false) }?.second
            ?: tokenToCode.firstOrNull { it.first.equals(token, ignoreCase = true) }?.second

    /** Resolves "25.000" vs "12.99" vs "1.299,50" vs "1,299.50" and applies K/rb multipliers. */
    fun parseNumber(raw: String, multiplier: String?): Double? {
        val s = raw.replace("\u00A0", "").replace(" ", "")
        val dots = s.count { it == '.' }
        val commas = s.count { it == ',' }

        val value: Double = try {
            when {
                dots > 0 && commas > 0 -> {
                    // The later separator is the decimal one
                    if (s.lastIndexOf('.') > s.lastIndexOf(',')) {
                        s.replace(",", "").toDouble()
                    } else {
                        s.replace(".", "").replace(',', '.').toDouble()
                    }
                }
                dots == 1 -> {
                    val digitsAfter = s.length - s.indexOf('.') - 1
                    // "25.000" → thousands, "12.99" → decimal
                    if (digitsAfter == 3) s.replace(".", "").toDouble() else s.toDouble()
                }
                dots > 1 -> s.replace(".", "").toDouble()
                commas == 1 -> {
                    val digitsAfter = s.length - s.indexOf(',') - 1
                    if (digitsAfter == 3) s.replace(",", "").toDouble()
                    else s.replace(',', '.').toDouble()
                }
                commas > 1 -> s.replace(",", "").toDouble()
                else -> s.toDouble()
            }
        } catch (e: NumberFormatException) {
            return null
        }

        val m = when (multiplier?.lowercase()) {
            "k", "rb", "ribu" -> 1_000.0
            else -> 1.0
        }
        return value * m
    }
}
