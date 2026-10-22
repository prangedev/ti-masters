package br.com.timasters.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.timasters.app.core.validation.AuthInputValidator
import br.com.timasters.app.core.validation.DocumentValidator
import br.com.timasters.app.data.business.AppointmentDao
import br.com.timasters.app.data.business.AppointmentInput
import br.com.timasters.app.data.business.AppointmentKind
import br.com.timasters.app.data.business.AppointmentStatus
import br.com.timasters.app.data.business.AppointmentWithClient
import br.com.timasters.app.data.business.BusinessRepository
import br.com.timasters.app.data.business.ClientDao
import br.com.timasters.app.data.business.ClientEntity
import br.com.timasters.app.data.business.ClientInput
import br.com.timasters.app.data.business.CompanyProfileDao
import br.com.timasters.app.data.business.CompanyRepository
import br.com.timasters.app.data.business.PricingSettingsDao
import br.com.timasters.app.data.business.ServiceItemDao
import br.com.timasters.app.data.business.ServiceOrderDao
import br.com.timasters.app.data.business.StockMovementDao
import br.com.timasters.app.data.business.OrderPhotoDao
import br.com.timasters.app.data.business.OrderRepository
import br.com.timasters.app.data.business.ReminderOption
import br.com.timasters.app.data.business.ServiceOrderWithClient
import br.com.timasters.app.data.business.TicketDao
import br.com.timasters.app.data.business.TicketInput
import br.com.timasters.app.data.business.TicketPriority
import br.com.timasters.app.data.business.TicketStatus
import br.com.timasters.app.data.business.TicketWithClient
import br.com.timasters.app.data.business.TimeEntryDao
import br.com.timasters.app.data.business.TimeEntryInput
import br.com.timasters.app.data.business.TimeEntryWithClient
import br.com.timasters.app.data.business.sync.BusinessSyncScheduler
import br.com.timasters.app.data.business.sync.BusinessSyncer
import br.com.timasters.app.data.business.sync.SyncError
import br.com.timasters.app.ui.screens.business.BusinessTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

private val WHILE_SUBSCRIBED = SharingStarted.WhileSubscribed(5_000)

// region Home and sync

sealed interface CompanyState {
    data object Loading : CompanyState

    /** First use without internet: the company id has never been downloaded. */
    data object Unavailable : CompanyState
    data class Ready(val companyId: String) : CompanyState
}

data class SyncInfo(
    val isSyncing: Boolean = false,
    val error: SyncError? = null,
    val pendingChanges: Int = 0,
    val lastSyncAt: Long? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BusinessHomeViewModel @Inject constructor(
    private val companyRepository: CompanyRepository,
    private val syncScheduler: BusinessSyncScheduler,
    syncer: BusinessSyncer,
    clientDao: ClientDao,
    ticketDao: TicketDao,
    appointmentDao: AppointmentDao,
    timeEntryDao: TimeEntryDao,
    serviceItemDao: ServiceItemDao,
    pricingSettingsDao: PricingSettingsDao,
    serviceOrderDao: ServiceOrderDao,
    companyProfileDao: CompanyProfileDao,
    orderPhotoDao: OrderPhotoDao,
    stockMovementDao: StockMovementDao
) : ViewModel() {

    private val lookupFailed = MutableStateFlow(false)

    val companyState: StateFlow<CompanyState> = combine(companyRepository.companyId, lookupFailed) { id, failed ->
        when {
            id != null -> CompanyState.Ready(id)
            failed -> CompanyState.Unavailable
            else -> CompanyState.Loading
        }
    }.stateIn(viewModelScope, WHILE_SUBSCRIBED, CompanyState.Loading)

    val syncInfo: StateFlow<SyncInfo> = companyRepository.companyId.flatMapLatest { id ->
        if (id == null) {
            flowOf(SyncInfo())
        } else {
            val pending = combine(
                listOf(
                    clientDao.observeDirtyCount(id),
                    ticketDao.observeDirtyCount(id),
                    appointmentDao.observeDirtyCount(id),
                    timeEntryDao.observeDirtyCount(id),
                    serviceItemDao.observeDirtyCount(id),
                    pricingSettingsDao.observeDirtyCount(id),
                    serviceOrderDao.observeDirtyCount(id),
                    companyProfileDao.observeDirtyCount(id),
                    orderPhotoDao.observeDirtyCount(id),
                    stockMovementDao.observeDirtyCount(id)
                )
            ) { counts -> counts.sum() }
            combine(syncer.state, pending, companyRepository.lastSyncAt(id)) { state, count, last ->
                SyncInfo(state.isSyncing, state.error, count, last)
            }
        }
    }.stateIn(viewModelScope, WHILE_SUBSCRIBED, SyncInfo())

    init {
        loadCompany()
    }

    fun loadCompany() {
        lookupFailed.value = false
        viewModelScope.launch {
            val found = try {
                companyRepository.ensureCompanyId()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            lookupFailed.value = found == null
            if (found != null) syncScheduler.syncNow()
        }
    }

    fun syncNow() = syncScheduler.syncNow()
}

// endregion

// region Lists

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ClientsViewModel @Inject constructor(
    companyRepository: CompanyRepository,
    repository: BusinessRepository
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val clients: StateFlow<List<ClientEntity>> = combine(
        companyRepository.companyId.filterNotNull().flatMapLatest(repository::clients),
        _query
    ) { all, query ->
        val text = query.trim()
        val digits = text.filter { it.isDigit() }
        if (text.isEmpty()) {
            all
        } else {
            all.filter { client ->
                client.name.contains(text, ignoreCase = true) ||
                    client.city?.contains(text, ignoreCase = true) == true ||
                    (digits.length >= 3 && (client.phone?.contains(digits) == true || client.docNumber?.contains(digits) == true))
            }
        }
    }.stateIn(viewModelScope, WHILE_SUBSCRIBED, emptyList())

    fun onQueryChange(value: String) {
        _query.value = value
    }
}

enum class TicketFilter { OPEN, DONE, ALL }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TicketsViewModel @Inject constructor(
    companyRepository: CompanyRepository,
    repository: BusinessRepository
) : ViewModel() {

    private val _filter = MutableStateFlow(TicketFilter.OPEN)
    val filter: StateFlow<TicketFilter> = _filter.asStateFlow()
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Open tickets: most urgent first, then the closest due date. */
    val tickets: StateFlow<List<TicketWithClient>> = combine(
        companyRepository.companyId.filterNotNull().flatMapLatest(repository::tickets),
        _filter,
        _query
    ) { all, filter, query ->
        all.filter { item ->
            val status = TicketStatus.from(item.ticket.status)
            when (filter) {
                TicketFilter.OPEN -> status.isOpen
                TicketFilter.DONE -> !status.isOpen
                TicketFilter.ALL -> true
            }
        }.filter { item ->
            query.isBlank() ||
                item.ticket.title.contains(query.trim(), ignoreCase = true) ||
                item.clientName.contains(query.trim(), ignoreCase = true) ||
                item.ticket.number?.toString() == query.trim().removePrefix("#")
        }.sortedWith(
            compareByDescending<TicketWithClient> { TicketStatus.from(it.ticket.status).isOpen }
                .thenByDescending { TicketPriority.from(it.ticket.priority).ordinal }
                .thenBy { it.ticket.dueAt ?: Long.MAX_VALUE }
                .thenByDescending { it.ticket.createdAt }
        )
    }.stateIn(viewModelScope, WHILE_SUBSCRIBED, emptyList())

    fun onFilterChange(value: TicketFilter) {
        _filter.value = value
    }

    fun onQueryChange(value: String) {
        _query.value = value
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AgendaViewModel @Inject constructor(
    companyRepository: CompanyRepository,
    repository: BusinessRepository
) : ViewModel() {

    /** From today on, grouped by day. */
    val days: StateFlow<List<Pair<LocalDate, List<AppointmentWithClient>>>> = companyRepository.companyId
        .filterNotNull()
        .flatMapLatest { repository.appointmentsFrom(it, BusinessTime.startOfToday()) }
        .map { list -> list.groupBy { BusinessTime.localDate(it.appointment.startsAt) }.toList() }
        .stateIn(viewModelScope, WHILE_SUBSCRIBED, emptyList())
}

data class TimeTotals(val todayMillis: Long = 0, val weekMillis: Long = 0, val monthMillis: Long = 0)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TimeViewModel @Inject constructor(
    private val companyRepository: CompanyRepository,
    private val repository: BusinessRepository
) : ViewModel() {

    private val companyId = companyRepository.companyId.filterNotNull()

    val running: StateFlow<TimeEntryWithClient?> = companyId.flatMapLatest(repository::runningTimer)
        .stateIn(viewModelScope, WHILE_SUBSCRIBED, null)

    val days: StateFlow<List<Pair<LocalDate, List<TimeEntryWithClient>>>> = companyId
        .flatMapLatest(repository::timeEntries)
        .map { list -> list.groupBy { BusinessTime.localDate(it.entry.startedAt) }.toList() }
        .stateIn(viewModelScope, WHILE_SUBSCRIBED, emptyList())

    val totals: StateFlow<TimeTotals> = days.map { groups ->
        val today = LocalDate.now(BusinessTime.zone)
        val weekStart = today.minusDays(today.dayOfWeek.value - 1L)
        var totals = TimeTotals()
        groups.forEach { (date, entries) ->
            val sum = entries.sumOf { it.entry.durationMillis() }
            totals = totals.copy(
                todayMillis = totals.todayMillis + if (date == today) sum else 0,
                weekMillis = totals.weekMillis + if (!date.isBefore(weekStart) && !date.isAfter(today)) sum else 0,
                monthMillis = totals.monthMillis + if (date.year == today.year && date.month == today.month) sum else 0
            )
        }
        totals
    }.stateIn(viewModelScope, WHILE_SUBSCRIBED, TimeTotals())

    val clients: StateFlow<List<ClientEntity>> = companyId.flatMapLatest(repository::clients)
        .stateIn(viewModelScope, WHILE_SUBSCRIBED, emptyList())

    fun openTicketsOf(clientId: String?): Flow<List<TicketWithClient>> =
        if (clientId == null) flowOf(emptyList()) else repository.ticketsOfClient(clientId)
            .map { list -> list.filter { TicketStatus.from(it.ticket.status).isOpen } }

    fun start(clientId: String, ticketId: String?) {
        viewModelScope.launch {
            val company = companyRepository.companyId.first() ?: return@launch
            repository.startTimer(company, clientId, ticketId)
        }
    }

    fun stop(entryId: String) {
        viewModelScope.launch { repository.stopTimer(entryId) }
    }
}

// endregion

// region Details

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ClientDetailViewModel @Inject constructor(
    private val companyRepository: CompanyRepository,
    private val repository: BusinessRepository,
    orderRepository: OrderRepository
) : ViewModel() {

    private val clientId = MutableStateFlow<String?>(null)

    val client: StateFlow<ClientEntity?> = clientId.filterNotNull().flatMapLatest(repository::client)
        .stateIn(viewModelScope, WHILE_SUBSCRIBED, null)
    val tickets: StateFlow<List<TicketWithClient>> = clientId.filterNotNull().flatMapLatest(repository::ticketsOfClient)
        .stateIn(viewModelScope, WHILE_SUBSCRIBED, emptyList())
    val appointments: StateFlow<List<AppointmentWithClient>> =
        clientId.filterNotNull().flatMapLatest(repository::appointmentsOfClient)
            .stateIn(viewModelScope, WHILE_SUBSCRIBED, emptyList())
    val timeEntries: StateFlow<List<TimeEntryWithClient>> =
        clientId.filterNotNull().flatMapLatest(repository::timeEntriesOfClient)
            .stateIn(viewModelScope, WHILE_SUBSCRIBED, emptyList())
    val orders: StateFlow<List<ServiceOrderWithClient>> =
        clientId.filterNotNull().flatMapLatest(orderRepository::ordersOfClient)
            .stateIn(viewModelScope, WHILE_SUBSCRIBED, emptyList())
    val running: StateFlow<TimeEntryWithClient?> = companyRepository.companyId.filterNotNull()
        .flatMapLatest(repository::runningTimer)
        .stateIn(viewModelScope, WHILE_SUBSCRIBED, null)

    fun load(id: String) {
        clientId.value = id
    }

    fun startTimer() {
        val id = clientId.value ?: return
        viewModelScope.launch {
            repository.startTimer(companyRepository.companyId.first() ?: return@launch, id, null)
        }
    }

    fun stopTimer(entryId: String) {
        viewModelScope.launch { repository.stopTimer(entryId) }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = clientId.value ?: return
        viewModelScope.launch {
            repository.deleteClient(id)
            onDeleted()
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TicketDetailViewModel @Inject constructor(
    private val companyRepository: CompanyRepository,
    private val repository: BusinessRepository,
    orderRepository: OrderRepository
) : ViewModel() {

    private val ticketId = MutableStateFlow<String?>(null)

    val ticket: StateFlow<TicketWithClient?> = ticketId.filterNotNull().flatMapLatest(repository::ticket)
        .stateIn(viewModelScope, WHILE_SUBSCRIBED, null)
    val timeEntries: StateFlow<List<TimeEntryWithClient>> =
        ticketId.filterNotNull().flatMapLatest(repository::timeEntriesOfTicket)
            .stateIn(viewModelScope, WHILE_SUBSCRIBED, emptyList())
    val orders: StateFlow<List<ServiceOrderWithClient>> =
        ticketId.filterNotNull().flatMapLatest(orderRepository::ordersOfTicket)
            .stateIn(viewModelScope, WHILE_SUBSCRIBED, emptyList())
    val running: StateFlow<TimeEntryWithClient?> = companyRepository.companyId.filterNotNull()
        .flatMapLatest(repository::runningTimer)
        .stateIn(viewModelScope, WHILE_SUBSCRIBED, null)

    fun load(id: String) {
        ticketId.value = id
    }

    fun setStatus(status: TicketStatus) {
        val id = ticketId.value ?: return
        viewModelScope.launch { repository.setTicketStatus(id, status) }
    }

    fun startTimer() {
        val current = ticket.value?.ticket ?: return
        viewModelScope.launch {
            repository.startTimer(companyRepository.companyId.first() ?: return@launch, current.clientId, current.id)
        }
    }

    fun stopTimer(entryId: String) {
        viewModelScope.launch { repository.stopTimer(entryId) }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = ticketId.value ?: return
        viewModelScope.launch {
            repository.deleteTicket(id)
            onDeleted()
        }
    }
}

// endregion

// region Forms

/** Fields of the client form; errors appear only after the first save attempt. */
data class ClientForm(
    val name: String = "",
    val document: String = "",
    val email: String = "",
    val phone: String = "",
    val address: String = "",
    val city: String = "",
    val notes: String = "",
    val showErrors: Boolean = false,
    val isLoaded: Boolean = false
) {
    val nameError: Boolean get() = name.isBlank()
    val documentError: Boolean get() = document.isNotBlank() && !DocumentValidator.isValid(document)
    val emailError: Boolean get() = email.isNotBlank() && !AuthInputValidator.isValidEmail(email)
    val phoneError: Boolean get() = phone.isNotBlank() && phone.filter { it.isDigit() }.length !in 10..13
    val isValid: Boolean get() = !nameError && !documentError && !emailError && !phoneError
}

@HiltViewModel
class ClientEditViewModel @Inject constructor(
    private val companyRepository: CompanyRepository,
    private val repository: BusinessRepository,
    private val clientDao: ClientDao
) : ViewModel() {

    private val _form = MutableStateFlow(ClientForm())
    val form: StateFlow<ClientForm> = _form.asStateFlow()
    private var editingId: String? = null

    private var session: String? = null

    /** A new [session] (form opened again) resets everything; the same one keeps the typing. */
    fun load(id: String?, session: String) {
        if (this.session == session) return
        this.session = session
        editingId = id
        _form.value = ClientForm()
        if (id == null) {
            _form.value = ClientForm(isLoaded = true)
            return
        }
        viewModelScope.launch {
            val client = clientDao.get(id) ?: return@launch
            _form.value = ClientForm(
                name = client.name,
                document = client.docNumber?.let(DocumentValidator::format).orEmpty(),
                email = client.email.orEmpty(),
                phone = client.phone.orEmpty(),
                address = client.address.orEmpty(),
                city = client.city.orEmpty(),
                notes = client.notes.orEmpty(),
                isLoaded = true
            )
        }
    }

    fun update(transform: (ClientForm) -> ClientForm) = _form.update(transform)

    fun save(onSaved: (String) -> Unit) {
        val form = _form.value
        if (!form.isValid) {
            _form.update { it.copy(showErrors = true) }
            return
        }
        viewModelScope.launch {
            val company = companyRepository.companyId.first() ?: return@launch
            val id = repository.saveClient(
                company,
                editingId,
                ClientInput(form.name, form.document, form.email, form.phone, form.address, form.city, form.notes)
            )
            onSaved(id)
        }
    }
}

data class TicketForm(
    val clientId: String? = null,
    val title: String = "",
    val description: String = "",
    val priority: TicketPriority = TicketPriority.MEDIUM,
    val status: TicketStatus = TicketStatus.OPEN,
    val dueDate: LocalDate? = null,
    val showErrors: Boolean = false,
    val isLoaded: Boolean = false
) {
    val clientError: Boolean get() = clientId == null
    val titleError: Boolean get() = title.isBlank()
    val isValid: Boolean get() = !clientError && !titleError
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TicketEditViewModel @Inject constructor(
    private val companyRepository: CompanyRepository,
    private val repository: BusinessRepository,
    private val ticketDao: TicketDao
) : ViewModel() {

    private val _form = MutableStateFlow(TicketForm())
    val form: StateFlow<TicketForm> = _form.asStateFlow()
    private var editingId: String? = null

    val clients: StateFlow<List<ClientEntity>> = companyRepository.companyId.filterNotNull()
        .flatMapLatest(repository::clients)
        .stateIn(viewModelScope, WHILE_SUBSCRIBED, emptyList())

    private var session: String? = null

    fun load(id: String?, presetClientId: String?, session: String) {
        if (this.session == session) return
        this.session = session
        editingId = id
        _form.value = TicketForm()
        if (id == null) {
            _form.value = TicketForm(clientId = presetClientId, isLoaded = true)
            return
        }
        viewModelScope.launch {
            val ticket = ticketDao.get(id) ?: return@launch
            _form.value = TicketForm(
                clientId = ticket.clientId,
                title = ticket.title,
                description = ticket.description.orEmpty(),
                priority = TicketPriority.from(ticket.priority),
                status = TicketStatus.from(ticket.status),
                dueDate = ticket.dueAt?.let(BusinessTime::localDate),
                isLoaded = true
            )
        }
    }

    fun update(transform: (TicketForm) -> TicketForm) = _form.update(transform)

    fun save(onSaved: (String) -> Unit) {
        val form = _form.value
        if (!form.isValid) {
            _form.update { it.copy(showErrors = true) }
            return
        }
        viewModelScope.launch {
            val company = companyRepository.companyId.first() ?: return@launch
            val id = repository.saveTicket(
                company,
                editingId,
                TicketInput(
                    clientId = form.clientId!!,
                    title = form.title,
                    description = form.description,
                    priority = form.priority,
                    status = form.status,
                    // Due at the end of the chosen day.
                    dueAt = form.dueDate?.let { BusinessTime.toMillis(it, LocalTime.of(23, 59)) }
                )
            )
            onSaved(id)
        }
    }
}

data class AppointmentForm(
    val clientId: String? = null,
    val ticketId: String? = null,
    val kind: AppointmentKind = AppointmentKind.ON_SITE,
    val date: LocalDate = LocalDate.now(BusinessTime.zone),
    val start: LocalTime = LocalTime.of(9, 0),
    val end: LocalTime = LocalTime.of(10, 0),
    val address: String = "",
    val notes: String = "",
    val reminder: ReminderOption = ReminderOption.HOUR_1,
    val status: AppointmentStatus = AppointmentStatus.SCHEDULED,
    val isExisting: Boolean = false,
    val showErrors: Boolean = false,
    val isLoaded: Boolean = false
) {
    val clientError: Boolean get() = clientId == null
    val timeError: Boolean get() = !end.isAfter(start)
    val isValid: Boolean get() = !clientError && !timeError
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AppointmentEditViewModel @Inject constructor(
    private val companyRepository: CompanyRepository,
    private val repository: BusinessRepository,
    private val clientDao: ClientDao
) : ViewModel() {

    private val _form = MutableStateFlow(AppointmentForm())
    val form: StateFlow<AppointmentForm> = _form.asStateFlow()
    private var editingId: String? = null

    val clients: StateFlow<List<ClientEntity>> = companyRepository.companyId.filterNotNull()
        .flatMapLatest(repository::clients)
        .stateIn(viewModelScope, WHILE_SUBSCRIBED, emptyList())

    val clientTickets: StateFlow<List<TicketWithClient>> = _form
        .map { it.clientId }
        .flatMapLatest { id -> if (id == null) emptyFlow() else repository.ticketsOfClient(id) }
        .stateIn(viewModelScope, WHILE_SUBSCRIBED, emptyList())

    private var session: String? = null

    fun load(id: String?, presetClientId: String?, presetTicketId: String?, session: String) {
        if (this.session == session) return
        this.session = session
        editingId = id
        _form.value = AppointmentForm()
        viewModelScope.launch {
            val existing = id?.let { repository.appointment(it) }
            _form.value = if (existing != null) {
                AppointmentForm(
                    clientId = existing.clientId,
                    ticketId = existing.ticketId,
                    kind = AppointmentKind.from(existing.kind),
                    date = BusinessTime.localDate(existing.startsAt),
                    start = BusinessTime.localTime(existing.startsAt),
                    end = BusinessTime.localTime(existing.endsAt),
                    address = existing.address.orEmpty(),
                    notes = existing.notes.orEmpty(),
                    reminder = ReminderOption.from(existing.reminderMinutes),
                    status = AppointmentStatus.from(existing.status),
                    isExisting = true,
                    isLoaded = true
                )
            } else {
                val client = presetClientId?.let { clientDao.get(it) }
                AppointmentForm(
                    clientId = client?.id,
                    ticketId = presetTicketId,
                    address = client.fullAddress(),
                    isLoaded = true
                )
            }
        }
    }

    fun update(transform: (AppointmentForm) -> AppointmentForm) = _form.update(transform)

    /** Picking a client fills in their address when the field is still empty. */
    fun selectClient(client: ClientEntity) = _form.update {
        it.copy(
            clientId = client.id,
            ticketId = if (it.clientId == client.id) it.ticketId else null,
            address = it.address.ifBlank { client.fullAddress() }
        )
    }

    fun save(onSaved: () -> Unit) {
        val form = _form.value
        if (!form.isValid) {
            _form.update { it.copy(showErrors = true) }
            return
        }
        viewModelScope.launch {
            val company = companyRepository.companyId.first() ?: return@launch
            repository.saveAppointment(
                company,
                editingId,
                AppointmentInput(
                    clientId = form.clientId!!,
                    ticketId = form.ticketId,
                    kind = form.kind,
                    startsAt = BusinessTime.toMillis(form.date, form.start),
                    endsAt = BusinessTime.toMillis(form.date, form.end),
                    address = form.address,
                    notes = form.notes,
                    reminder = form.reminder
                )
            )
            onSaved()
        }
    }

    fun setStatus(status: AppointmentStatus, onDone: () -> Unit) {
        val id = editingId ?: return
        viewModelScope.launch {
            repository.setAppointmentStatus(id, status)
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        val id = editingId ?: return
        viewModelScope.launch {
            repository.deleteAppointment(id)
            onDone()
        }
    }

    private fun ClientEntity?.fullAddress(): String =
        listOfNotNull(this?.address, this?.city).joinToString(", ")
}

data class TimeEntryForm(
    val clientId: String? = null,
    val ticketId: String? = null,
    val date: LocalDate = LocalDate.now(BusinessTime.zone),
    val start: LocalTime = LocalTime.of(9, 0),
    val end: LocalTime = LocalTime.of(10, 0),
    val note: String = "",
    val isExisting: Boolean = false,
    val showErrors: Boolean = false,
    val isLoaded: Boolean = false
) {
    val clientError: Boolean get() = clientId == null
    val timeError: Boolean get() = !end.isAfter(start)
    val isValid: Boolean get() = !clientError && !timeError
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TimeEntryEditViewModel @Inject constructor(
    private val companyRepository: CompanyRepository,
    private val repository: BusinessRepository
) : ViewModel() {

    private val _form = MutableStateFlow(TimeEntryForm())
    val form: StateFlow<TimeEntryForm> = _form.asStateFlow()
    private var editingId: String? = null

    val clients: StateFlow<List<ClientEntity>> = companyRepository.companyId.filterNotNull()
        .flatMapLatest(repository::clients)
        .stateIn(viewModelScope, WHILE_SUBSCRIBED, emptyList())

    val clientTickets: StateFlow<List<TicketWithClient>> = _form
        .map { it.clientId }
        .flatMapLatest { id -> if (id == null) emptyFlow() else repository.ticketsOfClient(id) }
        .stateIn(viewModelScope, WHILE_SUBSCRIBED, emptyList())

    private var session: String? = null

    fun load(id: String?, session: String) {
        if (this.session == session) return
        this.session = session
        editingId = id
        _form.value = TimeEntryForm()
        viewModelScope.launch {
            val existing = id?.let { repository.timeEntry(it) }
            _form.value = if (existing != null) {
                TimeEntryForm(
                    clientId = existing.clientId,
                    ticketId = existing.ticketId,
                    date = BusinessTime.localDate(existing.startedAt),
                    start = BusinessTime.localTime(existing.startedAt),
                    end = BusinessTime.localTime(existing.endedAt ?: System.currentTimeMillis()),
                    note = existing.note.orEmpty(),
                    isExisting = true,
                    isLoaded = true
                )
            } else {
                TimeEntryForm(isLoaded = true)
            }
        }
    }

    fun update(transform: (TimeEntryForm) -> TimeEntryForm) = _form.update(transform)

    fun save(onSaved: () -> Unit) {
        val form = _form.value
        if (!form.isValid) {
            _form.update { it.copy(showErrors = true) }
            return
        }
        viewModelScope.launch {
            val company = companyRepository.companyId.first() ?: return@launch
            repository.saveTimeEntry(
                company,
                editingId,
                TimeEntryInput(
                    clientId = form.clientId!!,
                    ticketId = form.ticketId,
                    startedAt = BusinessTime.toMillis(form.date, form.start),
                    endedAt = BusinessTime.toMillis(form.date, form.end),
                    note = form.note
                )
            )
            onSaved()
        }
    }

    fun delete(onDone: () -> Unit) {
        val id = editingId ?: return
        viewModelScope.launch {
            repository.deleteTimeEntry(id)
            onDone()
        }
    }
}

// endregion
