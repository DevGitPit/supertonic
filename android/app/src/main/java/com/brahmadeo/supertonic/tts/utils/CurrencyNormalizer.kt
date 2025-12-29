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

        // Rule 0: Remove commas from numbers
        // Use lookaround to avoid word boundary issues with symbols (e.g. $800,000)
        add("(?<!\\d)(\\d{1,3}(?:,\\d{3})+)(?!\\d)") { m ->
            m.group(0)?.replace(",", "") ?: ""
        }

        // Rule 1: Prefixed currencies with magnitude (SR3mn, RMB2bn)
        add("(C\\$|CA\\$|A\\$|AU\\$|US\\$|NZ\\$|HK\\$|S\\$|SR|RMB)(\\d+(?:\\.\\d+)?)(bn|mn|m|b|tn|k)") { m ->
            val prefix = m.group(1) ?: ""
            val amount = m.group(2) ?: ""
            val suffix = m.group(3) ?: ""
            
            var key = prefix.uppercase(Locale.ROOT)
            if (key.startsWith("S") && !key.contains("$") && key != "SR") key = key.replace("S", "S$")
            
            val currencyName = currencyPrefixes[key] ?: currencyPrefixes[key.replace("$", "")] ?: "dollars"
            val magnitude = expandMagnitude(suffix)
            val formattedAmount = formatAmount(amount)
            "$formattedAmount $magnitude $currencyName"
        }

        // Rule 2: ISO codes or prefixes with optional space and magnitude (SR 3mn, RMB 2bn)
        add("\\b(CAD|AUD|USD|GBP|EUR|INR|JPY|CNY|SGD|NZD|HKD|KRW|SR|RMB)\\s*(\\d+(?:\\.\\d+)?)(bn|mn|m|b|tn|k)") { m ->
            val code = m.group(1)?.uppercase(Locale.ROOT) ?: ""
            val amount = m.group(2) ?: ""
            val suffix = m.group(3) ?: ""
            
            val currencyName = currencyPrefixes[code] ?: code
            val magnitude = expandMagnitude(suffix)
            val formattedAmount = formatAmount(amount)
            "$formattedAmount $magnitude $currencyName"
        }

        // Rule 3: ISO code currencies without magnitude (CAD 500, SR 3000)
        add("\\b(CAD|AUD|USD|GBP|EUR|INR|JPY|CNY|SGD|NZD|HKD|KRW|SR|RMB)\\s*(\\d+(?:\\.\\d+)?)\\b") { m ->
            val code = m.group(1)?.uppercase(Locale.ROOT) ?: ""
            val amount = m.group(2) ?: ""
            
            val currencyName = currencyPrefixes[code] ?: code
            val formattedAmount = formatAmount(amount)
            "$formattedAmount $currencyName"
        }

        // Rule 4: Symbol + amount + magnitude (£800m, €500bn)
        add("($symPattern)(\\d+(?:\\.\\d+)?)(bn|mn|m|b|tn|k)\\b") { m ->
            val symbol = m.group(1) ?: "$"
            val amount = m.group(2) ?: ""
            val suffix = m.group(3) ?: ""
            
            val currencyName = currencySymbols[symbol] ?: "dollars"
            val magnitude = expandMagnitude(suffix)
            val formattedAmount = formatAmount(amount)
            "$formattedAmount $magnitude $currencyName"
        }

        // Rule 5: Parenthetical magnitudes (£800m ($1.08bn), (SR3mn))
        add("\\(($symPattern|C\\$|CA\\$|A\\$|AU\\$|US\\$|SR|RMB)(\\d+(?:\\.\\d+)?)(bn|mn|m|b|tn|k)\\)") { m ->
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
            
            val magnitude = expandMagnitude(suffix)
            val formattedAmount = formatAmount(amount)
            "equivalent to $formattedAmount $magnitude $currencyName"
        }

        // Rule 5b: Parenthetical whole amounts ($800,000), (SR 3000)
        add("\\(($symPattern|C\\$|CA\\$|A\\$|AU\\$|US\\$|SR|RMB)\\s*(\\d+(?:\\.\\d+)?)\\)") { m ->
            val symbol = m.group(1) ?: "$"
            val amount = m.group(2) ?: ""
            
            var key = symbol.uppercase(Locale.ROOT)
            if (key.startsWith("S") && !key.contains("$") && key != "SR") key = key.replace("S", "S$")

            val currencyName = if (key.length > 1) {
                currencyPrefixes[key] ?: currencyPrefixes[key.replace("$", "")] ?: "dollars"
            } else {
                currencySymbols[symbol] ?: "dollars"
            }
            
            val formattedAmount = formatAmount(amount)
            "equivalent to $formattedAmount $currencyName"
        }

        // Rule 6: Plain symbol + amount with decimals (£10.50)
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

        // Rule 7
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
