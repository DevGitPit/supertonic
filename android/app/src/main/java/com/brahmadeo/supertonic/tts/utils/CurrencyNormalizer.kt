package com.brahmadeo.supertonic.tts.utils

import java.text.NumberFormat
import java.util.Locale
import java.util.regex.Pattern

class CurrencyNormalizer {

    private val currencySymbols = mapOf(
        "$" to "dollars",
        "£" to "pounds",
        "€" to "euros",
        "₹" to "rupees",
        "¥" to "yen",
        "₩" to "won"
    )

    private val currencyPrefixes = mapOf(
        "C$" to "Canadian dollars",
        "CA$" to "Canadian dollars",
        "CAD" to "Canadian dollars",
        "A$" to "Australian dollars",
        "AU$" to "Australian dollars",
        "AUD" to "Australian dollars",
        "US$" to "US dollars",
        "USD" to "US dollars",
        "NZ$" to "New Zealand dollars",
        "HK$" to "Hong Kong dollars",
        "SGD" to "Singapore dollars",
        "S$" to "Singapore dollars",
        "GBP" to "British pounds",
        "EUR" to "euros",
        "INR" to "Indian rupees",
        "JPY" to "Japanese yen",
        "CNY" to "Chinese yuan",
        "KRW" to "South Korean won",
        "SR" to "Saudi Riyals",
        "RMB" to "Renminbi"
    )

    data class Rule(val pattern: Pattern, val replacement: (java.util.regex.Matcher) -> String)

    private val rules: List<Rule> = initializeCurrencyRules()

    private fun initializeCurrencyRules(): List<Rule> {
        val symPattern = "[£€₹¥₩$]"
        val rulesList = mutableListOf<Rule>()

        fun add(regex: String, replacement: (java.util.regex.Matcher) -> String) {
            rulesList.add(Rule(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), replacement))
        }

        // ----------------------------------------------------------------------
        // RULE 0: Global Comma Cleanup
        // ----------------------------------------------------------------------
        // Removes commas from numbers (e.g., "5,000" -> "5000") to make parsing easier.
        add("(\\d{1,3}(?:,\\d{3})+)") { m ->
            m.group(1)?.replace(",", "") ?: ""
        }

        // ----------------------------------------------------------------------
        // RULE 1: Dual Magnitude (The "Lakh Crore" Fix) -> High Priority
        // ----------------------------------------------------------------------
        // Matches: INR 42.7 lakh crore, ₹ 15.7 thousand crore
        add("\\b(INR|₹)\\s*([\\d.]+(?:\\.\\d+)?)\\s*(lakh|thousand|k)\\s*(crore|cr)\\b") { m ->
            val symbol = m.group(1)?.uppercase(Locale.ROOT) ?: ""
            val amount = m.group(2) ?: ""
            val mag1 = expandMagnitude(m.group(3) ?: "")
            val mag2 = expandMagnitude(m.group(4) ?: "")

            val currencyName = if (symbol == "₹" || symbol == "INR") "Indian rupees" else "rupees"
            val formattedAmount = formatAmount(amount)
            
            "$formattedAmount $mag1 $mag2 $currencyName"
        }

        // ----------------------------------------------------------------------
        // RULE 2: Parenthetical Currency Ranges
        // ----------------------------------------------------------------------
        // Matches: (INR5,000-INR10,000), (INR50crore-INR100crore)
        add("\\((CAD|AUD|USD|GBP|EUR|INR|JPY|CNY|SGD|NZD|HKD|KRW|SR|RMB)\\s*([\\d.]+(?:\\s*(?:crore|lakh|bn|mn|m|b|k))?)\\s*-\\s*\\1\\s*([\\d.]+(?:\\s*(?:crore|lakh|bn|mn|m|b|k))?)\\)") { m ->
            val code = m.group(1)?.uppercase(Locale.ROOT) ?: ""
            val val1 = m.group(2)?.trim() ?: ""
            val val2 = m.group(3)?.trim() ?: ""

            val currencyName = currencyPrefixes[code] ?: code
            "between $val1 and $val2 $currencyName"
        }

        // ----------------------------------------------------------------------
        // RULE 3: Prefixed currencies with magnitude
        // ----------------------------------------------------------------------
        // Matches: SR3mn, RMB2bn, USD 5 billion
        add("(C\\$|CA\\$|A\\$|AU\\$|US\\$|NZ\\$|HK\\$|S\\$|SR|RMB|CAD|AUD|USD|GBP|EUR|INR|JPY|CNY|SGD|NZD|HKD|KRW)\\s*([\\d.]+(?:\\.\\d+)?)\\s*(trillion|billion|million|crore|lakh|bn|mn|tn|m|b|k)") { m ->
            val prefix = m.group(1) ?: ""
            val amount = m.group(2) ?: ""
            val suffix = m.group(3) ?: ""

            var key = prefix.uppercase(Locale.ROOT)
            if (key.startsWith("S") && !key.contains("$") && key != "SR" && key != "SGD") key = key.replace("S", "S$")

            val currencyName = currencyPrefixes[key] ?: currencyPrefixes[key.replace("$", "")] ?: key
            val magnitude = expandMagnitude(suffix)
            val formattedAmount = formatAmount(amount)
            "$formattedAmount $magnitude $currencyName"
        }

        // ----------------------------------------------------------------------
        // RULE 4: Standard ISO code without magnitude
        // ----------------------------------------------------------------------
        // Matches: CAD 500, INR 5000
        add("\\b(CAD|AUD|USD|GBP|EUR|INR|JPY|CNY|SGD|NZD|HKD|KRW|SR|RMB)\\s*([\\d.]+(?:\\.\\d+)?)\\b") { m ->
            val code = m.group(1)?.uppercase(Locale.ROOT) ?: ""
            val amount = m.group(2) ?: ""

            val currencyName = currencyPrefixes[code] ?: code
            val formattedAmount = formatAmount(amount)
            "$formattedAmount $currencyName"
        }

        // ----------------------------------------------------------------------
        // RULE 5: Symbol + amount + magnitude
        // ----------------------------------------------------------------------
        // Matches: £800m, €500bn
        add("($symPattern)([\\d.]+(?:\\.\\d+)?)\\s*(trillion|billion|million|crore|lakh|bn|mn|tn|m|b|k)\\b") { m ->
            val symbol = m.group(1) ?: "$"
            val amount = m.group(2) ?: ""
            val suffix = m.group(3) ?: ""

            val currencyName = currencySymbols[symbol] ?: "dollars"
            val magnitude = expandMagnitude(suffix)
            val formattedAmount = formatAmount(amount)
            "$formattedAmount $magnitude $currencyName"
        }

        // ----------------------------------------------------------------------
        // RULE 6: Parenthetical Equivalents
        // ----------------------------------------------------------------------
        // Matches: ($1.08bn), (SR3mn)
        add("\\(($symPattern|C\\$|CA\\$|A\\$|AU\\$|US\\$|SR|RMB)\\s*([\\d.]+(?:\\.\\d+)?)(?:\\s*(trillion|billion|million|crore|lakh|bn|mn|tn|m|b|k))?\\)") { m ->
            val symbol = m.group(1) ?: "$"
            val amount = m.group(2) ?: ""
            val suffix = m.group(3) ?: ""

            var key = symbol.uppercase(Locale.ROOT)
            if (key.startsWith("S") && !key.contains("$") && key != "SR") key = key.replace("S", "S$")

            val currencyName = if (key.length > 1) {
                currencyPrefixes[key] ?: currencyPrefixes[key.replace("$", "")] ?: "dollars"
            } else {
                currencySymbols[symbol] ?: "dollars"
            }

            val magnitude = if (suffix.isNotEmpty()) expandMagnitude(suffix) else ""
            val formattedAmount = formatAmount(amount)
            
            val magString = if (magnitude.isNotEmpty()) " $magnitude" else ""
            "equivalent to $formattedAmount$magString $currencyName"
        }

        // ----------------------------------------------------------------------
        // RULE 7: Plain symbol + amount with decimals
        // ----------------------------------------------------------------------
        // Matches: £10.50, $5.99
        add("($symPattern)(\\d+)\\.(\\d{2})\\b") { m ->
            val symbol = m.group(1) ?: "$"
            val whole = m.group(2) ?: ""
            val cents = m.group(3) ?: ""

            val currencyName = currencySymbols[symbol] ?: "dollars"

            if (symbol == "₹" || symbol == "\u20B9") {
                val formattedWhole = formatIndianAmount(whole)
                if (cents == "00") {
                    "$formattedWhole rupees"
                } else {
                    "$formattedWhole rupees and $cents paise"
                }
            } else {
                if (cents == "00") {
                    "$whole $currencyName"
                } else {
                    "$whole $currencyName and $cents cents"
                }
            }
        }

        // ----------------------------------------------------------------------
        // RULE 8: Plain symbol + integer amount
        // ----------------------------------------------------------------------
        // Matches: $500, ₹100
        add("($symPattern)(\\d+)\\b") { m ->
            val symbol = m.group(1) ?: "$"
            val amount = m.group(2) ?: ""
            val currencyName = currencySymbols[symbol] ?: "dollars"

            if (symbol == "₹" || symbol == "\u20B9") {
                val formattedAmount = formatIndianAmount(amount)
                "$formattedAmount rupees"
            } else {
                "$amount $currencyName"
            }
        }

        return rulesList
    }

    private fun expandMagnitude(suffix: String): String {
        return when (suffix.lowercase(Locale.ROOT)) {
            "tn" -> "trillion"
            "bn" -> "billion"
            "mn", "m" -> "million"
            "b" -> "billion"
            "k" -> "thousand"
            "cr", "crore" -> "crore"
            "lakh" -> "lakh"
            else -> suffix
        }
    }

    private fun formatAmount(amount: String): String {
        return if (amount.contains(".")) {
            val parts = amount.split(".")
            "${parts[0]} point ${parts[1]}"
        } else {
            amount
        }
    }

    private fun formatIndianAmount(amountStr: String): String {
        // Just return the plain number, removing commas if any exist
        val num = amountStr.replace(",", "").toLongOrNull()
        return num?.toString() ?: amountStr
    }

    fun normalize(text: String): String {
        var normalized = text
        for (rule in rules) {
            val matcher = rule.pattern.matcher(normalized)
            val sb = StringBuffer()
            while (matcher.find()) {
                val replacement = rule.replacement(matcher).replace("\\", "\\\\").replace("$", "\\$")
                matcher.appendReplacement(sb, replacement)
            }
            matcher.appendTail(sb)
            normalized = sb.toString()
        }
        return normalized
    }
}
