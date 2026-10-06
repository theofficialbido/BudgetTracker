package com.bido.budgetsync.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SmsParserTest {
    private fun ok(body: String): ParsedSms {
        val parsed = SmsParser.parse(body)
        assertNotNull("should parse: $body", parsed)
        return parsed!!
    }

    @Test fun englishPurchaseWithMerchant() {
        val p = ok("Purchase of EGP 250.50 at CARREFOUR MAADI on 12/09 with card ending 1234")
        assertEquals(250.5, p.amount, 0.001)
        assertEquals("CARREFOUR MAADI", p.merchant)
    }

    @Test fun debitedIgnoresBalanceAmount() {
        val p = ok("Your account was debited with LE 1,200 at Amazon. Balance: LE 5,000")
        assertEquals(1200.0, p.amount, 0.001)
        assertEquals("Amazon", p.merchant)
    }

    @Test fun amountBeforeCurrency() {
        assertEquals(75.0, ok("POS transaction of 75 EGP approved").amount, 0.001)
    }

    @Test fun balanceFirstStillPicksSpending() {
        assertEquals(90.0, ok("Available balance EGP 4,000. You paid EGP 90 at Uber").amount, 0.001)
    }

    @Test fun arabicDigitsAndMerchant() {
        val p = ok("تم خصم مبلغ ٣٥٠ جنيه لدى كارفور")
        assertEquals(350.0, p.amount, 0.001)
        assertEquals("كارفور", p.merchant)
    }

    @Test fun arabicWithdrawalNoMerchant() {
        val p = ok("تم سحب 500 جنيه من ماكينة ATM")
        assertEquals(500.0, p.amount, 0.001)
    }

    @Test fun arabicPurchaseDecimalSeparator() {
        assertEquals(120.5, ok("تم شراء بمبلغ ١٢٠٫٥ جنيه عند سبينس").amount, 0.001)
    }

    @Test fun otpRejected() {
        assertNull(SmsParser.parse("Your OTP is 123456 for purchase of EGP 100"))
        assertNull(SmsParser.parse("رمز التحقق 4821 لعملية شراء بمبلغ 100 جنيه"))
    }

    @Test fun incomingMoneyRejected() {
        assertNull(SmsParser.parse("EGP 5,000 has been credited to your account"))
        assertNull(SmsParser.parse("تم إيداع 1000 جنيه في حسابك"))
        assertNull(SmsParser.parse("Refund of EGP 300 received"))
    }

    @Test fun creditNoticeMentioningAPurchaseLimitIsRejected() {
        assertNull(SmsParser.parse("Your account was credited with EGP 5,000. Daily purchase limit EGP 10,000"))
        assertNull(SmsParser.parse("تم إيداع 2000 جنيه. الحد اليومي للشراء 10000 جنيه"))
    }

    @Test fun debitStillAcceptedWhenTheTextAlsoMentionsCredit() {
        // a transfer: money leaves this account and is credited to someone else
        assertEquals(500.0, ok("EGP 500 debited from your account and credited to Ahmed").amount, 0.001)
    }

    @Test fun noAmountOrNoSpendingWordRejected() {
        assertNull(SmsParser.parse("Purchase approved at Carrefour"))
        assertNull(SmsParser.parse("Your balance is EGP 500"))
        assertNull(SmsParser.parse("Hello, how are you?"))
    }

    @Test fun wordsContainingLeAreNotCurrency() {
        assertNull(SmsParser.parse("Debit alert: SALE 50 items at store"))
    }
}
