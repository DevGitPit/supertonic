package com.brahmadeo.supertonic.tts.utils

object NumberUtils {

    private val units = arrayOf(
        "", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
        "eighteen", "nineteen"
    )

    private val tens = arrayOf(
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
    )

    fun convert(n: Long): String {
        if (n < 0) {
            return "minus " + convert(-n)
        }
        if (n == 0L) {
            return "zero"
        }
        if (n < 20) {
            return units[n.toInt()]
        }
        if (n < 100) {
            return tens[n.toInt() / 10] + (if (n % 10 != 0L) " " + units[(n % 10).toInt()] else "")
        }
        if (n < 1000) {
            return units[(n / 100).toInt()] + " hundred" + (if (n % 100 != 0L) " " + convert(n % 100) else "")
        }
        if (n < 1000000) {
            return convert(n / 1000) + " thousand" + (if (n % 1000 != 0L) " " + convert(n % 1000) else "")
        }
        if (n < 1000000000) {
            return convert(n / 1000000) + " million" + (if (n % 1000000 != 0L) " " + convert(n % 1000000) else "")
        }
        return convert(n / 1000000000) + " billion" + (if (n % 1000000000 != 0L) " " + convert(n % 1000000000) else "")
    }

    fun convertDouble(d: Double): String {
        val longVal = d.toLong()
        if (d == longVal.toDouble()) {
            return convert(longVal)
        }
        val s = d.toString()
        val parts = s.split(".")
        if (parts.size == 2) {
            val whole = convert(parts[0].toLong())
            val fraction = parts[1].map { 
                if (it.isDigit()) units[it.toString().toInt()] else "" 
            }.joinToString(" ")
            return "$whole point $fraction"
        }
        return convert(longVal)
    }
}
