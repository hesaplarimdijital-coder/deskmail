package com.deskmail.core.log

/**
 * Takes out of a line what must never be written down: passwords, tokens and authorisation codes,
 * wherever the protocols the app speaks put them. Every line of the log passes through here before
 * it is kept, whoever wrote it, so a careless call cannot leak a secret into a file the user shares.
 *
 * What is masked:
 * - IMAP `LOGIN user password` (the password) and `AUTHENTICATE <mechanism> <response>`,
 *   SMTP `AUTH <mechanism> <response>` (the response);
 * - HTTP `Authorization: Bearer|Basic <value>`, and `auth=Bearer <token>` inside a decoded XOAUTH2 response;
 * - OAuth and form fields by name (`access_token`, `refresh_token`, `id_token`, `code`,
 *   `code_verifier`, `client_secret`, `password`, …) as `name=value` and as JSON `"name":"value"`;
 * - anything shaped like a JSON Web Token.
 *
 * Addresses, subjects and server answers are left as they are: the log is for finding out what went wrong.
 */
object LogRedactor {
    const val MASK = "***"

    private val SECRET_FIELDS = listOf(
        "access_token", "refresh_token", "id_token", "token", "code", "code_verifier", "client_secret",
        "client_assertion", "assertion", "password", "passwd", "pwd", "secret",
    ).joinToString("|")

    private val rules: List<Pair<Regex, String>> = listOf(
        // IMAP: a1 LOGIN "user" "password". Commands are matched as the protocols write them, in
        // capitals, so that a sentence ("login to host as user") is left alone.
        Regex("""\b(LOGIN\s+(?:"[^"]*"|\S+)\s+)(?:"(?:[^"\\]|\\.)*"|\S+)""") to "$1$MASK",
        // IMAP AUTHENTICATE / SMTP AUTH with an initial response; not a server listing its
        // mechanisms ("250-AUTH LOGIN XOAUTH2")
        Regex("""(?<!250[- ])\b((?:AUTHENTICATE|AUTH)\s+[A-Z0-9_-]+\s+)\S+""") to "$1$MASK",
        // HTTP header
        Regex("""(?i)\b(Authorization\s*:\s*(?:Bearer|Basic)\s+)\S+""") to "$1$MASK",
        // a decoded XOAUTH2 response: user=…^Aauth=Bearer …^A^A
        Regex("""(?i)\b(auth=Bearer\s+)[^\s\u0001]+""") to "$1$MASK",
        // name=value in a URL, a form body or a sentence
        Regex("""(?i)(?<![A-Za-z0-9_])((?:$SECRET_FIELDS)=)[^&\s"',;]+""") to "$1$MASK",
        // "name": "value" in JSON
        Regex("""(?i)("(?:$SECRET_FIELDS)"\s*:\s*)"(?:[^"\\]|\\.)*"""") to "$1\"$MASK\"",
        // a JSON Web Token, wherever it turns up
        Regex("""\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]*""") to MASK,
    )

    fun redact(text: String): String {
        var result = text
        for ((pattern, replacement) in rules) result = pattern.replace(result, replacement)
        return result
    }
}
