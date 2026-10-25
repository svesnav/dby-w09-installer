package org.matepad.installer

import java.util.Locale
import java.util.TimeZone

/** A language of the system: its locale and the keyboard layouts that go with it. */
data class Language(val label: String, val locale: String, val keyboard: String)

/** XKB layouts as Plasma and the console take them, e.g. "us,ru". */
data class KeyboardLayout(val label: String, val xkb: String)

val LANGUAGES = listOf(
    Language("English (US)", "en_US.UTF-8", "us"),
    Language("English (UK)", "en_GB.UTF-8", "gb"),
    Language("Deutsch", "de_DE.UTF-8", "de"),
    Language("Español", "es_ES.UTF-8", "es"),
    Language("Français", "fr_FR.UTF-8", "fr"),
    Language("Italiano", "it_IT.UTF-8", "it"),
    Language("Nederlands", "nl_NL.UTF-8", "us"),
    Language("Polski", "pl_PL.UTF-8", "pl"),
    Language("Português (Brasil)", "pt_BR.UTF-8", "br"),
    Language("Türkçe", "tr_TR.UTF-8", "tr"),
    Language("Русский", "ru_RU.UTF-8", "us,ru"),
    Language("Українська", "uk_UA.UTF-8", "us,ua"),
    Language("中文 (简体)", "zh_CN.UTF-8", "us"),
    Language("日本語", "ja_JP.UTF-8", "jp"),
)

val KEYBOARD_LAYOUTS = listOf(
    KeyboardLayout("English (US)", "us"),
    KeyboardLayout("English (UK)", "gb"),
    KeyboardLayout("German", "de"),
    KeyboardLayout("Spanish", "es"),
    KeyboardLayout("French", "fr"),
    KeyboardLayout("Italian", "it"),
    KeyboardLayout("Polish", "pl"),
    KeyboardLayout("Portuguese (Brazil)", "br"),
    KeyboardLayout("Turkish", "tr"),
    KeyboardLayout("English (US) + Russian", "us,ru"),
    KeyboardLayout("English (US) + Ukrainian", "us,ua"),
    KeyboardLayout("Japanese", "jp"),
)

/** Area/City zones, as /usr/share/zoneinfo has them. */
val TIME_ZONES: List<String> by lazy {
    TimeZone.getAvailableIDs().filter {
        '/' in it && !it.startsWith("Etc/") && !it.startsWith("SystemV/") && it[0].isUpperCase()
    }.distinct().sorted()
}

private val RESERVED_USERS = setOf(
    "root", "daemon", "bin", "sys", "sync", "games", "man", "lp", "mail", "news", "uucp", "proxy",
    "www-data", "backup", "list", "irc", "nobody", "systemd-network", "systemd-resolve", "messagebus",
    "sddm", "oem", "admin", "sudo", "users", "matepad",
)

/** What Kubuntu's first-boot setup asks; the installer applies it on the card instead. */
data class SystemSetup(
    val fullName: String = "",
    val userName: String = "",
    val password: String = "",
    val password2: String = "",
    val hostname: String = "matepad",
    val language: Language = defaultLanguage(),
    val keyboard: KeyboardLayout = KEYBOARD_LAYOUTS.firstOrNull { it.xkb == defaultLanguage().keyboard }
        ?: KEYBOARD_LAYOUTS.first(),
    val timezone: String = TimeZone.getDefault().id.takeIf { it in TIME_ZONES } ?: "Europe/London",
    /** SDDM logs the user in: the login screen has no on-screen keyboard yet. */
    val autologin: Boolean = true,
) {
    val userNameError: String?
        get() = when {
            userName.isEmpty() -> null
            !Regex("[a-z][a-z0-9_-]{0,31}").matches(userName) ->
                "lower-case letters, digits, - and _; starting with a letter"
            userName in RESERVED_USERS -> "this name is reserved"
            else -> null
        }
    val passwordError: String?
        get() = if (password2.isNotEmpty() && password != password2) "the passwords differ" else null
    val hostnameError: String?
        get() = if (hostname.isNotEmpty() && !Regex("[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?").matches(hostname))
            "letters, digits and -" else null
    val valid: Boolean
        get() = fullName.isNotBlank() && userName.isNotEmpty() && userNameError == null &&
            password.isNotEmpty() && password == password2 && hostname.isNotEmpty() && hostnameError == null &&
            timezone in TIME_ZONES

    /** key=value lines for matepad.sh configure; the password last, read from stdin. */
    fun toInput(): String = buildString {
        fun line(k: String, v: String) = append(k).append('=').append(v.replace("\n", " ")).append('\n')
        line("FULLNAME", fullName.trim())
        line("USERNAME", userName)
        line("HOSTNAME", hostname)
        line("LOCALE", language.locale)
        line("KEYBOARD", keyboard.xkb)
        line("TIMEZONE", timezone)
        line("AUTOLOGIN", if (autologin) "1" else "0")
        line("PASSWORD", password)
    }

    companion object {
        /** A user name from the full name: first word, lower case, ASCII only. */
        fun suggestUserName(fullName: String): String =
            fullName.trim().split(Regex("\\s+")).firstOrNull().orEmpty().lowercase(Locale.ROOT)
                .filter { it in 'a'..'z' || it in '0'..'9' }.let { if (it.firstOrNull()?.isLetter() == true) it else "" }
    }
}

private fun defaultLanguage(): Language {
    val l = Locale.getDefault()
    val tag = "${l.language}_${l.country}"
    return LANGUAGES.firstOrNull { it.locale.startsWith("$tag.") }
        ?: LANGUAGES.firstOrNull { it.locale.startsWith(l.language + "_") }
        ?: LANGUAGES.first()
}
