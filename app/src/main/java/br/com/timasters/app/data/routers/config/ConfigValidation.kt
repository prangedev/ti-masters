package br.com.timasters.app.data.routers.config

import androidx.annotation.StringRes
import br.com.timasters.app.R
import br.com.timasters.app.core.network.SubnetUtils

/** Checks the form before anything is sent to the router. Returns the problems found (empty = OK). */
object ConfigValidation {

    private val ROS_NAME = Regex("^[A-Za-z0-9_-]{1,15}$")

    @StringRes
    fun validate(task: ConfigTask): List<Int> = when (task) {
        is ConfigTask.Pppoe -> buildList {
            if (task.port.isBlank()) add(R.string.config_error_port)
            if (task.user.isBlank()) add(R.string.config_error_pppoe_user)
            if (task.password.isBlank()) add(R.string.config_error_pppoe_password)
            if (task.vlanId != null && task.vlanId !in 1..4094) add(R.string.config_error_vlan)
        }
        is ConfigTask.Dhcp -> validateDhcp(task)
        is ConfigTask.Bridge -> buildList {
            if (!ROS_NAME.matches(task.name)) add(R.string.config_error_bridge_name)
            if (task.ports.isEmpty()) add(R.string.config_error_bridge_ports)
            if (task.upstreamGateway != null && !SubnetUtils.isValidIpv4(task.upstreamGateway)) add(R.string.config_error_gateway)
        }
        is ConfigTask.Mesh -> buildList {
            if (task.network.isBlank() || task.network.length > 32) add(R.string.config_error_mesh_name)
            if (task.password.length !in 8..63) add(R.string.config_error_mesh_password)
            if (task.lan.isBlank()) add(R.string.config_error_port)
        }
    }

    private fun validateDhcp(task: ConfigTask.Dhcp): List<Int> = buildList {
        val gateway = SubnetUtils.ipv4ToInt(task.gateway)
        val start = SubnetUtils.ipv4ToInt(task.rangeStart)
        val end = SubnetUtils.ipv4ToInt(task.rangeEnd)
        if (task.lan.isBlank()) add(R.string.config_error_port)
        if (gateway == null) add(R.string.config_error_gateway)
        if (task.prefix !in 8..30) add(R.string.config_error_prefix)
        if (start == null || end == null) add(R.string.config_error_range)
        if (task.dns.split(',').map { it.trim() }.any { !SubnetUtils.isValidIpv4(it) }) add(R.string.config_error_dns)
        if (task.leaseHours !in 1..168) add(R.string.config_error_lease)
        if (gateway == null || start == null || end == null || task.prefix !in 8..30) return@buildList
        val mask = prefixMask(task.prefix)
        val network = gateway and mask
        val broadcast = network or mask.inv()
        val inSubnet = { ip: Int -> (ip and mask) == network && ip != network && ip != broadcast }
        if (!inSubnet(start) || !inSubnet(end) || Integer.compareUnsigned(start, end) > 0) add(R.string.config_error_range)
        else if (Integer.compareUnsigned(gateway, start) >= 0 && Integer.compareUnsigned(gateway, end) <= 0) add(R.string.config_error_gateway_in_range)
    }

    fun prefixMask(prefix: Int): Int = if (prefix == 0) 0 else -1 shl (32 - prefix)

    /** "192.168.88.1", 24 → "192.168.88.0/24". */
    fun networkCidr(gateway: String, prefix: Int): String? =
        SubnetUtils.ipv4ToInt(gateway)?.let { SubnetUtils.intToIpv4(it and prefixMask(prefix)) + "/$prefix" }

    /** "255.255.255.0" → 24; null when not a valid contiguous mask. */
    fun netmaskToPrefix(netmask: String): Int? {
        val value = SubnetUtils.ipv4ToInt(netmask) ?: return null
        val prefix = Integer.bitCount(value)
        return prefix.takeIf { prefixMask(it) == value }
    }
}
