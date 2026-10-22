package br.com.timasters.app.core.validation

enum class DocumentType { CPF, CNPJ }

/**
 * CPF and CNPJ validation/formatting.
 * Supports the alphanumeric CNPJ issued since July 2026: the first 12 characters may be
 * A-Z or 0-9, the 2 check digits stay numeric, and each character is worth (ASCII code - 48).
 */
object DocumentValidator {

    private val CNPJ_FIRST_WEIGHTS = intArrayOf(5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
    private val CNPJ_SECOND_WEIGHTS = intArrayOf(6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)

    /** Removes dots, dashes, slashes and spaces, and upper-cases letters. */
    fun normalize(raw: String): String = raw.filter { it.isLetterOrDigit() }.uppercase()

    fun detectType(raw: String): DocumentType? = when (normalize(raw).length) {
        11 -> DocumentType.CPF
        14 -> DocumentType.CNPJ
        else -> null
    }

    fun isValid(raw: String): Boolean = when (detectType(raw)) {
        DocumentType.CPF -> isValidCpf(raw)
        DocumentType.CNPJ -> isValidCnpj(raw)
        null -> false
    }

    fun isValidCpf(raw: String): Boolean {
        val cpf = normalize(raw)
        if (cpf.length != 11 || !cpf.all { it.isDigit() }) return false
        if (cpf.all { it == cpf[0] }) return false
        val digits = cpf.map { it - '0' }
        val first = cpfCheckDigit(digits, 9)
        val second = cpfCheckDigit(digits, 10)
        return digits[9] == first && digits[10] == second
    }

    fun isValidCnpj(raw: String): Boolean {
        val cnpj = normalize(raw)
        if (cnpj.length != 14) return false
        val base = cnpj.substring(0, 12)
        val check = cnpj.substring(12)
        if (!base.all { it in '0'..'9' || it in 'A'..'Z' }) return false
        if (!check.all { it.isDigit() }) return false
        if (cnpj.all { it == cnpj[0] }) return false
        val values = base.map { it.code - '0'.code }
        val first = cnpjCheckDigit(values, CNPJ_FIRST_WEIGHTS)
        val second = cnpjCheckDigit(values + first, CNPJ_SECOND_WEIGHTS)
        return check[0] - '0' == first && check[1] - '0' == second
    }

    /** "123.456.789-09" or "12.ABC.345/01DE-35". Invalid lengths are returned unchanged. */
    fun format(raw: String): String {
        val doc = normalize(raw)
        return when (doc.length) {
            11 -> "${doc.substring(0, 3)}.${doc.substring(3, 6)}.${doc.substring(6, 9)}-${doc.substring(9)}"
            14 -> "${doc.substring(0, 2)}.${doc.substring(2, 5)}.${doc.substring(5, 8)}/" +
                "${doc.substring(8, 12)}-${doc.substring(12)}"
            else -> raw
        }
    }

    private fun cpfCheckDigit(digits: List<Int>, length: Int): Int {
        val sum = (0 until length).sumOf { digits[it] * (length + 1 - it) }
        val rest = (sum * 10) % 11
        return if (rest == 10) 0 else rest
    }

    private fun cnpjCheckDigit(values: List<Int>, weights: IntArray): Int {
        val sum = values.indices.sumOf { values[it] * weights[it] }
        val rest = sum % 11
        return if (rest < 2) 0 else 11 - rest
    }
}
