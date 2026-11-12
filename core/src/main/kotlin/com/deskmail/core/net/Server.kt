package com.deskmail.core.net

/** How a connection to a mail server is secured. */
enum class Security {
    /** TLS from the first byte (IMAP on 993, SMTP on 465). */
    Tls,

    /** Plain at first, TLS before anything else is said (IMAP on 143, SMTP on 587). Refused where the server cannot. */
    StartTls,

    /** No encryption at all: for the servers inside the tests. The app never offers it. */
    None,
}

/** Where a mail server is reached. */
data class ServerAddress(val host: String, val port: Int, val security: Security) {
    override fun toString() = "$host:$port (${security.name})"
}

/** What an account signs in with. Neither kind prints its secret. */
sealed interface Credentials {
    val username: String

    /** A password, or the app password a provider hands out for mail programs. */
    class Password(override val username: String, val password: String) : Credentials {
        override fun toString() = "Password($username)"
    }

    /** An OAuth2 access token (XOAUTH2). */
    class OAuth(override val username: String, val accessToken: String) : Credentials {
        override fun toString() = "OAuth($username)"
    }
}
