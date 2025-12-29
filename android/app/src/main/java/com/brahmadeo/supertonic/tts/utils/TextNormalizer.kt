package com.brahmadeo.supertonic.tts.utils

import java.util.regex.Pattern

/**
 * Enhanced TextNormalizer with comprehensive rule set
 * Handles currencies, numbers, abbreviations, and more for natural TTS
 */
class TextNormalizer {
    private val currencyNormalizer = CurrencyNormalizer()
    
    data class Rule(val pattern: Pattern, val replacement: (java.util.regex.Matcher) -> String)
    private val rules: List<Rule> = initializeRules()

    private fun initializeRules(): List<Rule> {
        val rulesList = mutableListOf<Rule>()
        
        fun addStr(regex: String, replacement: String) {
            rulesList.add(Rule(Pattern.compile(regex, Pattern.CASE_INSENSITIVE)) { replacement })
        }
        
        fun addLambda(regex: String, replacement: (java.util.regex.Matcher) -> String) {
            rulesList.add(Rule(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), replacement))
        }

        // EMERGENCY NUMBERS (Priority: Highest)
        addStr("\\b911\\b", "nine one one")
        addLambda("\\b(999|112|000)\\b") { m -> 
            val num = m.group(1) ?: ""
            num.toCharArray().joinToString(" ") 
        }

        // MEASUREMENTS
        addLambda("\\b(\\d+(?:\\.\\d+)?)\\s*m\\b(?=[^a-zA-Z]|$)") { m ->
            val amount = m.group(1) ?: ""
            val valStr = if (amount.contains(".")) amount.replace(".", " point ") else amount
            if (amount == "1") "1 meter" else "$valStr meters"
        }

        addLambda("\\b(\\d+(?:\\.\\d+)?)(km|mi)\\b") { m ->
            val amount = m.group(1) ?: ""
            val unit = m.group(2)?.lowercase() ?: ""
            val valStr = if (amount.contains(".")) amount.replace(".", " point ") else amount
            val fullUnit = if (unit == "km") "kilometers" else "miles"
            "$valStr $fullUnit"
        }

        addLambda("\\b(\\d+(?:\\.\\d+)?)(kph|mph|kmh|km/h|m/s)\\b") { m ->
            val amount = m.group(1) ?: ""
            val unit = m.group(2)?.lowercase() ?: ""
            val valStr = if (amount.contains(".")) amount.replace(".", " point ") else amount
            val fullUnit = when (unit) {
                "kph", "kmh", "km/h" -> "kilometers per hour"
                "mph" -> "miles per hour"
                "m/s" -> "meters per second"
                else -> unit
            }
            "$valStr $fullUnit"
        }

        addLambda("\\b(\\d+(?:\\.\\d+)?)(kg|g|lb|lbs)\\b") { m ->
            val amount = m.group(1) ?: ""
            val unit = m.group(2)?.lowercase() ?: ""
            val valStr = if (amount.contains(".")) amount.replace(".", " point ") else amount
            val fullUnit = when (unit) {
                "kg" -> "kilograms"
                "g" -> "grams"
                "lb", "lbs" -> "pounds"
                else -> unit
            }
            if (amount == "1") "1 ${fullUnit.trimEnd('s')}" else "$valStr $fullUnit"
        }

        addLambda("\\b(\\d+(?:\\.\\d+)?)h\\b") { m ->
            val amount = m.group(1) ?: ""
            val valStr = if (amount.contains(".")) amount.replace(".", " point ") else amount
            if (amount == "1") "1 hour" else "$valStr hours"
        }

        // LARGE NUMBERS (Non-currency)
        addLambda("\\b(\\d+(?:\\.\\d+)?)\\s*(?:M|mn)\\b") { m ->
            val amount = m.group(1) ?: ""
            val valStr = if (amount.contains(".")) amount.replace(".", " point ") else amount
            "$valStr million"
        }

        addLambda("\\b(\\d+(?:\\.\\d+)?)\\s*(?:B|bn)\\b") { m ->
            val amount = m.group(1) ?: ""
            val valStr = if (amount.contains(".")) amount.replace(".", " point ") else amount
            "$valStr billion"
        }

        addLambda("\\b(\\d+(?:\\.\\d+)?)tn\\b") { m ->
            val amount = m.group(1) ?: ""
            val valStr = if (amount.contains(".")) amount.replace(".", " point ") else amount
            "$valStr trillion"
        }

        // PERCENTAGES
        addLambda("\\b(\\d+(?:\\.\\d+)?)%") { m ->
            val amount = m.group(1) ?: ""
            val valStr = if (amount.contains(".")) amount.replace(".", " point ") else amount
            "$valStr percent"
        }

        // ORDINALS
        addLambda("\\b(\\d+)(st|nd|rd|th)\\b") { m ->
            val num = m.group(1)?.toIntOrNull() ?: 0
            numberToOrdinal(num)
        }

        // YEARS (Split 4 digit years starting with 19 or 20)
        addLambda("\\b(19|20)(\\d{2})\\b(?!s)") { m ->
            "${m.group(1)} ${m.group(2)}"
        }

        // TITLES
        addLambda("\\b(Prof|Dr|Mr|Mrs|Ms)\\.\\s+") { m ->
            when (m.group(1)) {
                "Prof" -> "Professor "
                "Dr" -> "Doctor "
                "Mr" -> "Mister "
                "Mrs" -> "Missus "
                "Ms" -> "Miss "
                else -> m.group(0) ?: ""
            }
        }

        // ABBREVIATIONS
        addLambda("\\b(approx|vs|etc)\\.\\b") { m ->
            when (m.group(1)?.lowercase()) {
                "approx" -> "approximately"
                "vs" -> "versus"
                "etc" -> "et cetera"
                else -> m.group(0) ?: ""
            }
        }

        return rulesList
    }

    private fun numberToOrdinal(num: Int): String {
        val ordinals = mapOf(
            1 to "first", 2 to "second", 3 to "third", 4 to "fourth", 5 to "fifth",
            6 to "sixth", 7 to "seventh", 8 to "eighth", 9 to "ninth", 10 to "tenth",
            11 to "eleventh", 12 to "twelfth", 13 to "thirteenth", 14 to "fourteenth",
            15 to "fifteenth", 16 to "sixteenth", 17 to "seventeenth", 18 to "eighteenth",
            19 to "nineteenth", 20 to "twentieth"
        )
        
        if (ordinals.containsKey(num)) return ordinals[num]!!
        
        val tens = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
        val ones = arrayOf("", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth")
        
        if (num < 100) {
            val tenDigit = num / 10
            val oneDigit = num % 10
            if (oneDigit == 0) return "${tens[tenDigit]}th"
            return "${tens[tenDigit]} ${ones[oneDigit]}"
        }
        
        val lastTwo = num % 100
        if (lastTwo in 11..13) return "${num}th"
        
        val lastDigit = num % 10
        return when (lastDigit) {
            1 -> "${num}st"
            2 -> "${num}nd"
            3 -> "${num}rd"
            else -> "${num}th"
        }
    }

    fun normalize(text: String): String {
        // Step 0: Fix smushed text from webpage layouts
        // Fix smushed sentences: lowercase char, period, uppercase char (reserved.Reuse)
        val smushedSentencePattern = Pattern.compile("([a-z])\\.([A-Z])")
        var fixedText = smushedSentencePattern.matcher(text).replaceAll("$1. $2")
        
        // Fix smushed words: lowercase char, uppercase char (economyIMF)
        val smushedWordPattern1 = Pattern.compile("([a-z])([A-Z])")
        fixedText = smushedWordPattern1.matcher(fixedText).replaceAll("$1 $2")
        
        // Fix smushed words: Uppercase followed by Uppercase+Lowercase (FTNews)
        val smushedWordPattern2 = Pattern.compile("([A-Z])([A-Z][a-z])")
        fixedText = smushedWordPattern2.matcher(fixedText).replaceAll("$1 $2")

        // Fix letter-number merges (Published8 -> Published 8)
        val letterNumberPattern = Pattern.compile("([a-zA-Z])(\\d)")
        fixedText = letterNumberPattern.matcher(fixedText).replaceAll("$1 $2")

        // Break up navigation menu "soup" (Skip to main content...)
        // Insert period before specific keywords if not already preceded by punctuation
        val navPattern = Pattern.compile("(?<![.!?]\\s)\\b(Skip to|Sign In|Subscribe|OPEN SIDE|MENU|Add to myFT|Save|Print this page|Published|Copyright|©)\\b", Pattern.CASE_INSENSITIVE)
        fixedText = navPattern.matcher(fixedText).replaceAll(". $1")

        // Step 1: Currency
        var normalized = currencyNormalizer.normalize(fixedText)
        
        // Step 2: Other rules
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

    fun splitIntoSentences(text: String): List<String> {
        val abbreviations = listOf(
            "Mr.", "Mrs.", "Dr.", "Ms.", "Prof.", "Sr.", "Jr.", 
            "etc.", "vs.", "e.g.", "i.e.",
            "Jan.", "Feb.", "Mar.", "Apr.", "May.", "Jun.", 
            "Jul.", "Aug.", "Sep.", "Oct.", "Nov.", "Dec.",
            "U.S.", "U.K.", "E.U."
        )
        var protectedText = text
        
        abbreviations.forEachIndexed { index, abbr ->
            val placeholder = "__ABBR${index}__"
            protectedText = protectedText.replace(abbr, placeholder, ignoreCase = true)
        }
        
        // Split by:
        // 1. Punctuation (.!?) followed by optional quote and space and Capital
        // 2. Semi-colon or Em-dash followed by space
        val pattern = Pattern.compile("(?<=[.!?])['\"”’]?\\s+(?=['\"“‘]?[A-Z])|(?<=[;—])\\s+")
        val rawSentences = protectedText.split(pattern)
        
        return rawSentences.map { sentence ->
            var restored = sentence
            abbreviations.forEachIndexed { index, abbr ->
                val placeholder = "__ABBR${index}__"
                restored = restored.replace(placeholder, abbr, ignoreCase = true)
            }
            restored.trim()
        }.filter { it.isNotEmpty() }
    }
}
