package com.smartpay.sync

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class SmsRecord(
    val id: String,
    val sender: String,
    val senderNumber: String,
    val amountBdt: Double,
    val feeBdt: Double,
    val balanceAfter: Double,
    val trxId: String,
    val smsTimeStr: String,
    val message: String,
    var status: String, 
    val time: Long,
    val dateText: String,
    val source: String, 
    var serverNote: String = ""
)

object SmsParser {
    // Official bKash headers
    private val STRICT_OFFICIAL_SENDERS = setOf("bkash", "16216")

    private val IGNORED_PHRASES = listOf(
        "Cash-Out", "Cash out", "Payment to", "Your bKash verification",
        "<#> NEVER share", "Never Share", "Your One Time Password", "Your security code",
        "Send Money", "Add Money", "Mobile Recharge", "Received Recharge",
        "Your bKash Mobile Recharge", "Bill successfully paid", "Your Digital Loan",
        "Savings", "Priyo Numbers", "Congratulations"
    )

    private val SERVER_BKASH_REGEX = Regex(
        """^You have received Tk\s*([\d,]+(?:\.\d{2})?)\s+from\s+(01\d{9})\.?(?:\s+Ref\s+[^\r\n]*?\.)?\s+(?:Fee|Commission)\s+Tk\s*([\d,]+(?:\.\d{2})?)\.\s+Balance\s+Tk\s*([\d,]+(?:\.\d{2})?)\.\s+TrxID\s+([A-Z0-9]{10})\s+at\s+(\d{2}/\d{2}/\d{4}\s+\d{2}:\d{2})\s*$""",
        RegexOption.IGNORE_CASE
    )

    private val FALLBACK_TRX_REGEX = Regex("""TrxID\s+([A-Z0-9]{8,12})""", RegexOption.IGNORE_CASE)
    private val FALLBACK_AMOUNT_REGEX = Regex("""received\s+Tk\s*([\d,]+(?:\.\d{2})?)""", RegexOption.IGNORE_CASE)
    private val FALLBACK_FROM_REGEX = Regex("""from\s+(01\d{9})""", RegexOption.IGNORE_CASE)
    private val FALLBACK_TIME_REGEX = Regex("""(\d{2}/\d{2}/\d{4}\s+\d{2}:\d{2})""")

    fun isStrictOfficialBkashHeader(address: String?): Boolean {
        if (address.isNullOrBlank()) return false
        val clean = address.trim()
        if (clean.startsWith("+") || clean.startsWith("880") || clean.startsWith("01") || clean.length > 10) {
            return false
        }
        return STRICT_OFFICIAL_SENDERS.contains(clean.lowercase(Locale.ROOT))
    }

    fun isIgnoredMessage(body: String): Boolean {
        val clean = body.trim()
        return IGNORED_PHRASES.any { phrase ->
            clean.startsWith(phrase, ignoreCase = true) || clean.contains("verification code", ignoreCase = true)
        }
    }

    fun parseIncomingOrManual(rawAddress: String, rawBody: String, source: String = "Auto"): SmsRecord? {
        if (source != "Manual" && !isStrictOfficialBkashHeader(rawAddress)) {
            return null
        }

        val normalized = rawBody.replace(Regex("""\r?\n"""), " ").replace(Regex("""\s+"""), " ").trim()
        if (normalized.isEmpty() || isIgnoredMessage(normalized)) return null

        val now = System.currentTimeMillis()
        val dateText = SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.getDefault()).format(Date(now))
        val eventId = "evt_" + UUID.randomUUID().toString().replace("-", "").take(20)

        val strictMatch = SERVER_BKASH_REGEX.matchEntire(normalized)
        if (strictMatch != null) {
            val amount = strictMatch.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.0
            val senderNum = strictMatch.groupValues[2]
            val fee = strictMatch.groupValues[3].replace(",", "").toDoubleOrNull() ?: 0.0
            val balance = strictMatch.groupValues[4].replace(",", "").toDoubleOrNull() ?: 0.0
            val trxId = strictMatch.groupValues[5].uppercase(Locale.ROOT)
            val smsTime = strictMatch.groupValues[6]

            return SmsRecord(
                id = eventId,
                sender = "bKash",
                senderNumber = senderNum,
                amountBdt = amount,
                feeBdt = fee,
                balanceAfter = balance,
                trxId = trxId,
                smsTimeStr = smsTime,
                message = normalized,
                status = "Pending",
                time = now,
                dateText = dateText,
                source = source
            )
        }

        if (normalized.contains("You have received Tk", ignoreCase = true) && normalized.contains("TrxID", ignoreCase = true)) {
            val trxId = FALLBACK_TRX_REGEX.find(normalized)?.groupValues?.get(1)?.uppercase(Locale.ROOT) ?: return null
            val amount = FALLBACK_AMOUNT_REGEX.find(normalized)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: 0.0
            val senderNum = FALLBACK_FROM_REGEX.find(normalized)?.groupValues?.get(1) ?: "01XXXXXXXXX"
            val smsTime = FALLBACK_TIME_REGEX.find(normalized)?.groupValues?.get(1)
                ?: SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(now))

            return SmsRecord(
                id = eventId,
                sender = "bKash",
                senderNumber = senderNum,
                amountBdt = amount,
                feeBdt = 0.0,
                balanceAfter = amount,
                trxId = trxId,
                smsTimeStr = smsTime,
                message = normalized,
                status = "Pending",
                time = now,
                dateText = dateText,
                source = source
            )
        }

        return null
    }
}