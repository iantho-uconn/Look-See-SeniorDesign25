package looksee.angelll.com.models

/** Codable credential-shaped value retained for parity with LoginCredentials.swift. */
data class LoginCredentials(
    val id: Int? = null,
    val username: String,
    val email: String? = null,
    val password: String,
)
