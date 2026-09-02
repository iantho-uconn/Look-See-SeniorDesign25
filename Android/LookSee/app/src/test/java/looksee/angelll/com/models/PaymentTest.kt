package looksee.angelll.com.models

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentTest {
    @Test
    fun legacyPaymentRecordRoundTripsAllBillingFields() {
        val original = Payment(
            id = 4,
            cardProvider = "Visa",
            cardNum = "4242",
            expireMonth = 12,
            expireYear = 2030,
            cvv = 123,
            firstName = "Ian",
            lastName = "Thompson",
            state = "CT",
            city = "Storrs",
            postCode = "06269",
            address1 = "1 Campus Road",
            address2 = "Suite 2",
            phone = "8605550100",
        )

        val decoded = Gson().fromJson(Gson().toJson(original), Payment::class.java)

        assertEquals(original, decoded)
    }

    @Test
    fun optionalPaymentIdentityAndSecondAddressRemainNullable() {
        val decoded = Gson().fromJson(
            """{"cardProvider":"Visa","cardNum":"4242","expireMonth":1,"expireYear":2030,"cvv":123,"firstName":"A","lastName":"B","state":"CT","city":"Storrs","postCode":"06269","address1":"Road","phone":"8605550100"}""",
            Payment::class.java,
        )

        assertNull(decoded.id)
        assertNull(decoded.address2)
    }
}
