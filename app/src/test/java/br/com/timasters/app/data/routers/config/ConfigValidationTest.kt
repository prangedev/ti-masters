package br.com.timasters.app.data.routers.config

import br.com.timasters.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigValidationTest {

    private fun dhcp(start: String = "192.168.88.100", end: String = "192.168.88.200", gateway: String = "192.168.88.1") =
        ConfigTask.Dhcp("bridge", gateway, 24, start, end, "1.1.1.1,8.8.8.8", 12)

    @Test
    fun dhcp_acceptsValidRange() {
        assertEquals(emptyList<Int>(), ConfigValidation.validate(dhcp()))
    }

    @Test
    fun dhcp_rejectsRangeOutsideSubnetReversedOrContainingGateway() {
        assertTrue(R.string.config_error_range in ConfigValidation.validate(dhcp(end = "192.168.89.10")))
        assertTrue(R.string.config_error_range in ConfigValidation.validate(dhcp(start = "192.168.88.200", end = "192.168.88.100")))
        assertTrue(R.string.config_error_range in ConfigValidation.validate(dhcp(end = "192.168.88.255")))
        assertTrue(R.string.config_error_gateway_in_range in ConfigValidation.validate(dhcp(gateway = "192.168.88.150")))
    }

    @Test
    fun pppoe_requiresCredentialsAndValidVlan() {
        val errors = ConfigValidation.validate(ConfigTask.Pppoe("ether1", 5000, "", ""))
        assertEquals(listOf(R.string.config_error_pppoe_user, R.string.config_error_pppoe_password, R.string.config_error_vlan), errors)
    }

    @Test
    fun meshAndBridge_checkNamesAndPasswords() {
        assertEquals(listOf(R.string.config_error_mesh_password), ConfigValidation.validate(ConfigTask.Mesh("Casa", "curta", null, "bridge")))
        assertEquals(
            listOf(R.string.config_error_bridge_name, R.string.config_error_bridge_ports),
            ConfigValidation.validate(ConfigTask.Bridge("nome com espaço", emptyList(), null))
        )
    }

    @Test
    fun netmaskAndCidrHelpers() {
        assertEquals(24, ConfigValidation.netmaskToPrefix("255.255.255.0"))
        assertEquals(null, ConfigValidation.netmaskToPrefix("255.0.255.0"))
        assertEquals("10.0.0.0/8", ConfigValidation.networkCidr("10.20.30.1", 8))
    }

    @Test
    fun lanHttp_decodesChunkedBodies() {
        val raw = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\n[{\"a\r\n5\r\n\":1}]\r\n0\r\n\r\n".toByteArray()
        val response = LanHttpClient.parse(raw)!!
        assertEquals(200, response.status)
        assertEquals("[{\"a\":1}]", response.body)
    }

    @Test
    fun lanHttp_detectsTruncatedBodiesByContentLength() {
        val full = "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\n[{}]".toByteArray()
        val cut = full.copyOf(full.size - 1)
        assertTrue(LanHttpClient.parse(full)!!.complete)
        assertEquals("[{}]", LanHttpClient.parse(full)!!.body)
        assertFalse(LanHttpClient.parse(cut)!!.complete)
        assertTrue(LanHttpClient.parse("HTTP/1.1 204 No Content\r\n\r\n".toByteArray())!!.complete)
        assertFalse(LanHttpClient.parse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\n[{}]\r\n".toByteArray())!!.complete)
    }
}
