package br.com.timasters.app.ui.screens.business

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import br.com.timasters.app.data.business.OrderKind
import java.util.UUID

/**
 * Screens inside the Business tab, kept as a small back stack (survives rotation).
 * Edit screens carry a [session] id created on every opening, so a form opened again starts
 * empty while a rotation (same session) keeps what was typed.
 */
sealed interface BusinessRoute {
    data object Home : BusinessRoute
    data class ClientDetail(val id: String) : BusinessRoute
    data class ClientEdit(val id: String?, val session: String = newSession()) : BusinessRoute
    data class TicketDetail(val id: String) : BusinessRoute
    data class TicketEdit(val id: String?, val clientId: String? = null, val session: String = newSession()) : BusinessRoute
    data class AppointmentEdit(
        val id: String?,
        val clientId: String? = null,
        val ticketId: String? = null,
        val session: String = newSession()
    ) : BusinessRoute
    data class TimeEntryEdit(val id: String?, val session: String = newSession()) : BusinessRoute
    data class OrderDetail(val id: String) : BusinessRoute
    data class OrderEdit(
        val id: String?,
        val kind: OrderKind = OrderKind.QUOTE,
        val clientId: String? = null,
        val ticketId: String? = null,
        val session: String = newSession()
    ) : BusinessRoute
    data class CompanySettings(val session: String = newSession()) : BusinessRoute
    data object Catalog : BusinessRoute
    data class CatalogItemEdit(val id: String?, val session: String = newSession()) : BusinessRoute
}

private fun newSession(): String = UUID.randomUUID().toString()

private fun BusinessRoute.encode(): String = when (this) {
    BusinessRoute.Home -> "home"
    is BusinessRoute.ClientDetail -> "client|$id"
    is BusinessRoute.ClientEdit -> "client_edit|${id.orEmpty()}|$session"
    is BusinessRoute.TicketDetail -> "ticket|$id"
    is BusinessRoute.TicketEdit -> "ticket_edit|${id.orEmpty()}|${clientId.orEmpty()}|$session"
    is BusinessRoute.AppointmentEdit -> "appointment_edit|${id.orEmpty()}|${clientId.orEmpty()}|${ticketId.orEmpty()}|$session"
    is BusinessRoute.TimeEntryEdit -> "time_edit|${id.orEmpty()}|$session"
    is BusinessRoute.OrderDetail -> "order|$id"
    is BusinessRoute.OrderEdit -> "order_edit|${id.orEmpty()}|${kind.serverId}|${clientId.orEmpty()}|${ticketId.orEmpty()}|$session"
    is BusinessRoute.CompanySettings -> "company|$session"
    BusinessRoute.Catalog -> "catalog"
    is BusinessRoute.CatalogItemEdit -> "catalog_edit|${id.orEmpty()}|$session"
}

private fun decode(value: String): BusinessRoute {
    val parts = value.split('|')
    fun arg(index: Int) = parts.getOrNull(index)?.ifEmpty { null }
    return when (parts.first()) {
        "client" -> BusinessRoute.ClientDetail(arg(1).orEmpty())
        "client_edit" -> BusinessRoute.ClientEdit(arg(1), arg(2) ?: newSession())
        "ticket" -> BusinessRoute.TicketDetail(arg(1).orEmpty())
        "ticket_edit" -> BusinessRoute.TicketEdit(arg(1), arg(2), arg(3) ?: newSession())
        "appointment_edit" -> BusinessRoute.AppointmentEdit(arg(1), arg(2), arg(3), arg(4) ?: newSession())
        "time_edit" -> BusinessRoute.TimeEntryEdit(arg(1), arg(2) ?: newSession())
        "order" -> BusinessRoute.OrderDetail(arg(1).orEmpty())
        "order_edit" -> BusinessRoute.OrderEdit(arg(1), OrderKind.from(arg(2)), arg(3), arg(4), arg(5) ?: newSession())
        "company" -> BusinessRoute.CompanySettings(arg(1) ?: newSession())
        "catalog" -> BusinessRoute.Catalog
        "catalog_edit" -> BusinessRoute.CatalogItemEdit(arg(1), arg(2) ?: newSession())
        else -> BusinessRoute.Home
    }
}

val BusinessBackStackSaver: Saver<List<BusinessRoute>, Any> = listSaver(
    save = { stack -> stack.map { it.encode() } },
    restore = { saved -> saved.map(::decode) }
)
