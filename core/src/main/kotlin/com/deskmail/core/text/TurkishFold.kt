package com.deskmail.core.text

import java.text.Normalizer

/**
 * Folds text for searching: lower case, without diacritics, with the Turkish letters on their
 * plain Latin neighbours (ş → s, ğ → g, ı and İ → i, ö → o, ü → u, ç → c). The search index stores
 * folded text and queries are folded the same way, so "sarki" finds "Şarkı" and "ISPARTA" finds
 * "ısparta" whatever the device's locale is.
 */
object TurkishFold {

    fun fold(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            when {
                // the dot of İ, the cedilla of ş, the breve of ğ, every other accent
                Character.getType(ch) == Character.NON_SPACING_MARK.toInt() -> Unit
                // the dotless ı has no decomposition; I must not become ı under a Turkish locale
                ch == 'ı' || ch == 'I' -> out.append('i')
                else -> out.append(ch.lowercaseChar())
            }
        }
        return out.toString()
    }
}
