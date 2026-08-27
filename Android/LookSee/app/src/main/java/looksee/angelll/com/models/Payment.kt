package looksee.angelll.com.models

/** Legacy billing-address/card record translated from Payment.swift. */
data class Payment(
    val id: Int? = null,
    val cardProvider: String,
    val cardNum: String,
    val expireMonth: Int,
    val expireYear: Int,
    val cvv: Int,
    val firstName: String,
    val lastName: String,
    val state: String,
    val city: String,
    val postCode: String,
    val address1: String,
    val address2: String? = null,
    val phone: String,
)
