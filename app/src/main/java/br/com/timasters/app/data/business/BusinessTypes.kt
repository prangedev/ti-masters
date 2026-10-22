package br.com.timasters.app.data.business

import androidx.annotation.StringRes
import br.com.timasters.app.R

/** Each enum's [serverId] is the value stored in the database (check constraints on the server). */
enum class TicketPriority(val serverId: String, @param:StringRes val labelResId: Int) {
    LOW("baixa", R.string.priority_low),
    MEDIUM("media", R.string.priority_medium),
    HIGH("alta", R.string.priority_high),
    URGENT("urgente", R.string.priority_urgent);

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.serverId == value } ?: MEDIUM
    }
}

enum class TicketStatus(val serverId: String, @param:StringRes val labelResId: Int, val isOpen: Boolean) {
    OPEN("aberto", R.string.ticket_status_open, true),
    IN_PROGRESS("em_andamento", R.string.ticket_status_in_progress, true),
    WAITING("aguardando", R.string.ticket_status_waiting, true),
    RESOLVED("resolvido", R.string.ticket_status_resolved, false),
    CLOSED("fechado", R.string.ticket_status_closed, false);

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.serverId == value } ?: OPEN
    }
}

enum class AppointmentKind(val serverId: String, @param:StringRes val labelResId: Int) {
    ON_SITE("visita", R.string.appointment_kind_on_site),
    REMOTE("remoto", R.string.appointment_kind_remote);

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.serverId == value } ?: ON_SITE
    }
}

enum class AppointmentStatus(val serverId: String, @param:StringRes val labelResId: Int) {
    SCHEDULED("agendado", R.string.appointment_status_scheduled),
    DONE("concluido", R.string.appointment_status_done),
    CANCELED("cancelado", R.string.appointment_status_canceled);

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.serverId == value } ?: SCHEDULED
    }
}

/** Reminder choices offered when scheduling, in minutes before the start. */
enum class ReminderOption(val minutes: Int?, @param:StringRes val labelResId: Int) {
    NONE(null, R.string.reminder_none),
    MIN_15(15, R.string.reminder_15_min),
    HOUR_1(60, R.string.reminder_1_hour),
    DAY_1(1440, R.string.reminder_1_day);

    companion object {
        fun from(minutes: Int?) = entries.firstOrNull { it.minutes == minutes } ?: NONE
    }
}

enum class ItemKind(val serverId: String, @param:StringRes val labelResId: Int) {
    SERVICE("servico", R.string.item_kind_service),
    PRODUCT("produto", R.string.item_kind_product);

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.serverId == value } ?: SERVICE
    }
}

enum class OrderKind(val serverId: String, @param:StringRes val labelResId: Int) {
    QUOTE("orcamento", R.string.order_kind_quote),
    SERVICE_ORDER("os", R.string.order_kind_service_order);

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.serverId == value } ?: QUOTE
    }
}

/** Quotes and service orders have different statuses; [kind] says which documents use each one. */
enum class OrderStatus(
    val serverId: String,
    val kind: OrderKind,
    @param:StringRes val labelResId: Int,
    val isOpen: Boolean
) {
    DRAFT("rascunho", OrderKind.QUOTE, R.string.order_status_draft, true),
    SENT("enviado", OrderKind.QUOTE, R.string.order_status_sent, true),
    APPROVED("aprovado", OrderKind.QUOTE, R.string.order_status_approved, false),
    REJECTED("recusado", OrderKind.QUOTE, R.string.order_status_rejected, false),
    OPEN("aberta", OrderKind.SERVICE_ORDER, R.string.order_status_open, true),
    IN_PROGRESS("em_execucao", OrderKind.SERVICE_ORDER, R.string.order_status_in_progress, true),
    DONE("concluida", OrderKind.SERVICE_ORDER, R.string.order_status_done, false),
    CANCELED("cancelada", OrderKind.SERVICE_ORDER, R.string.order_status_canceled, false);

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.serverId == value } ?: DRAFT
        fun of(kind: OrderKind) = entries.filter { it.kind == kind }
        fun initial(kind: OrderKind) = if (kind == OrderKind.QUOTE) DRAFT else OPEN
    }
}
