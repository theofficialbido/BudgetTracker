package com.bido.budgetsync.sms

data class ParsedSms(val amount: Double, val merchant: String)

/**
 * Generic bank-SMS rules (English and Arabic): an amount next to EGP / LE / جنيه, a spending word,
 * and no OTP or incoming-money wording. Returns null when the message should be ignored.
 */
object SmsParser {
    private val currency = """(?:EGP|E\.G\.P|LE|L\.E|E£|جنيه|ج\.م)"""
    private val number = """(\d[\d,]*(?:\.\d+)?)"""
    private val amountAfter = Regex("""(?<![\p{L}])$currency\.?\s*$number""", RegexOption.IGNORE_CASE)
    private val amountBefore = Regex("""$number\s*$currency(?![\p{L}])""", RegexOption.IGNORE_CASE)
    private val balanceHint = Regex("""(balance|available|avail|limit|رصيد|متاح)""", RegexOption.IGNORE_CASE)

    private val otpEn = Regex("""\b(otp|one[- ]time|verification|passcode|password|cvv|pin|code)\b""", RegexOption.IGNORE_CASE)
    private val otpAr = listOf("رمز", "كود", "كلمة السر", "كلمة المرور", "التحقق")
    private val spendEn = Regex(
        """\b(purchase|purchased|debited|debit|paid|payment|spent|withdrawal|withdrawn|withdraw|charged|pos|transaction|txn)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val spendAr = listOf("خصم", "شراء", "سحب", "دفع", "مدفوعات", "عملية")
    private val incomingEn = Regex("""\b(credited|received|deposit|deposited|refund|refunded|reversal|reversed|salary|cashback)\b""", RegexOption.IGNORE_CASE)
    private val incomingAr = listOf("إيداع", "ايداع", "أودع", "استلام", "استلمت", "استرداد", "راتب", "إضافة", "اضافة", "وارد")

    private val merchantRe = Regex(
        """(?<![\p{L}])(?:at|@|لدى|عند)\s*(.+?)(?=\s+(?:on|with|using|via|ref|card|ending|بتاريخ|بطاقة|بكارت|كارت|رصيد|يوم|الساعة)(?![\p{L}])|[,;\n]|\.(?:\s|$)|$)""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(body: String): ParsedSms? {
        val text = normalize(body)
        if (otpEn.containsMatchIn(text) || otpAr.any { it in text }) return null
        val spending = spendEn.containsMatchIn(text) || spendAr.any { it in text }
        val incoming = incomingEn.containsMatchIn(text) || incomingAr.any { it in text }
        if (!spending || (incoming && !spending)) return null
        val amount = findAmount(text) ?: return null
        return ParsedSms(amount, findMerchant(text))
    }

    private fun normalize(body: String): String {
        val sb = StringBuilder(body.length)
        for (c in body) sb.append(
            when (c) {
                in '٠'..'٩' -> '0' + (c - '٠')
                in '۰'..'۹' -> '0' + (c - '۰')
                '٫' -> '.'
                '٬' -> ','
                else -> c
            }
        )
        return sb.toString()
    }

    private fun findAmount(text: String): Double? {
        val hits = (amountAfter.findAll(text) + amountBefore.findAll(text))
            .sortedBy { it.range.first }
            .filter { !balanceHint.containsMatchIn(text.substring(maxOf(0, it.range.first - 20), it.range.first)) }
        return hits.firstNotNullOfOrNull { it.groupValues[1].replace(",", "").toDoubleOrNull()?.takeIf { v -> v > 0 } }
    }

    private fun findMerchant(text: String): String {
        val m = merchantRe.find(text)?.groupValues?.get(1)?.trim()?.trimEnd('.', ' ') ?: return ""
        return if (m.length in 2..40 && !m.first().isDigit()) m else ""
    }
}
