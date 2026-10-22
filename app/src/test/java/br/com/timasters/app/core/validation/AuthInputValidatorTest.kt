package br.com.timasters.app.core.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthInputValidatorTest {

    @Test
    fun email_acceptsCommonAddresses_andRejectsMalformedOnes() {
        assertTrue(AuthInputValidator.isValidEmail("tecnico@empresa.com.br"))
        assertTrue(AuthInputValidator.isValidEmail("  Joao.Silva+os@gmail.com  "))
        assertFalse(AuthInputValidator.isValidEmail("tecnico@empresa"))
        assertFalse(AuthInputValidator.isValidEmail("tecnico.empresa.com"))
        assertFalse(AuthInputValidator.isValidEmail(""))
    }

    @Test
    fun normalizeEmail_trimsAndLowercases() {
        assertEquals("joao@gmail.com", AuthInputValidator.normalizeEmail("  Joao@Gmail.COM "))
    }

    @Test
    fun password_requiresLengthLetterAndDigit() {
        assertTrue(AuthInputValidator.isValidPassword("senha123"))
        assertFalse(AuthInputValidator.isValidPassword("abc123"))
        assertFalse(AuthInputValidator.isValidPassword("somenteletras"))
        assertFalse(AuthInputValidator.isValidPassword("12345678"))
    }

    @Test
    fun name_requiresAtLeastTwoCharacters() {
        assertTrue(AuthInputValidator.isValidName("Ana"))
        assertFalse(AuthInputValidator.isValidName(" A "))
    }
}
