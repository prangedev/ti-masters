package br.com.timasters.app.core.validation

/** Client-side checks for the sign-in/sign-up forms. The server validates again. */
object AuthInputValidator {

    const val MIN_PASSWORD_LENGTH = 8
    const val MAX_NAME_LENGTH = 120

    // Pragmatic check (something@domain.tld); android.util.Patterns is not available in unit tests.
    private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$")

    fun normalizeEmail(email: String): String = email.trim().lowercase()

    fun isValidEmail(email: String): Boolean = EMAIL_REGEX.matches(normalizeEmail(email))

    /** At least [MIN_PASSWORD_LENGTH] characters, with at least one letter and one digit. */
    fun isValidPassword(password: String): Boolean =
        password.length >= MIN_PASSWORD_LENGTH &&
            password.any { it.isLetter() } &&
            password.any { it.isDigit() }

    fun isValidName(name: String): Boolean = name.trim().length in 2..MAX_NAME_LENGTH
}
