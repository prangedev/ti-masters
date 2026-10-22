package br.com.timasters.app.core.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentValidatorTest {

    @Test
    fun cpf_validAndInvalid() {
        assertTrue(DocumentValidator.isValidCpf("529.982.247-25"))
        assertTrue(DocumentValidator.isValidCpf("52998224725"))
        assertFalse(DocumentValidator.isValidCpf("529.982.247-26"))
        assertFalse(DocumentValidator.isValidCpf("111.111.111-11"))
        assertFalse(DocumentValidator.isValidCpf("1234"))
    }

    @Test
    fun cnpj_numericValidAndInvalid() {
        assertTrue(DocumentValidator.isValidCnpj("11.222.333/0001-81"))
        assertFalse(DocumentValidator.isValidCnpj("11.222.333/0001-82"))
        assertFalse(DocumentValidator.isValidCnpj("00.000.000/0000-00"))
    }

    @Test
    fun cnpj_alphanumericFromJuly2026() {
        assertTrue(DocumentValidator.isValidCnpj("12.ABC.345/01DE-35"))
        assertTrue(DocumentValidator.isValidCnpj("12abc34501de35"))
        assertFalse(DocumentValidator.isValidCnpj("12.ABC.345/01DE-36"))
        assertFalse(DocumentValidator.isValidCnpj("12.ABC.345/01DE-3A"))
    }

    @Test
    fun detectTypeAndFormat() {
        assertEquals(DocumentType.CPF, DocumentValidator.detectType("529.982.247-25"))
        assertEquals(DocumentType.CNPJ, DocumentValidator.detectType("12ABC34501DE35"))
        assertNull(DocumentValidator.detectType("123"))
        assertEquals("529.982.247-25", DocumentValidator.format("52998224725"))
        assertEquals("12.ABC.345/01DE-35", DocumentValidator.format("12abc34501de35"))
        assertTrue(DocumentValidator.isValid("11222333000181"))
    }
}
