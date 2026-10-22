package br.com.timasters.app.ui.viewmodel

import androidx.annotation.StringRes
import br.com.timasters.app.R
import br.com.timasters.app.data.lan.NoLocalNetworkException
import java.io.IOException
import java.net.UnknownHostException

/** Failures of the network tools, each with a pt-BR message. */
enum class ToolError(@param:StringRes val messageResId: Int) {
    INVALID_HOST(R.string.tool_error_invalid_host),
    INVALID_PORTS(R.string.tool_error_invalid_ports),
    HOST_NOT_FOUND(R.string.tool_error_host_not_found),
    NO_LOCAL_NETWORK(R.string.tool_error_no_local_network),
    NETWORK(R.string.tool_error_network),
    UNKNOWN(R.string.tool_error_unknown)
}

fun Throwable.toToolError(): ToolError = when (this) {
    is UnknownHostException -> ToolError.HOST_NOT_FOUND
    is NoLocalNetworkException -> ToolError.NO_LOCAL_NETWORK
    is IllegalArgumentException -> ToolError.INVALID_HOST
    is IOException -> ToolError.NETWORK
    else -> ToolError.UNKNOWN
}
