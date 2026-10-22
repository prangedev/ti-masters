package br.com.timasters.app.ui.viewmodel

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.timasters.app.core.format.MoneyInput
import br.com.timasters.app.core.validation.AuthInputValidator
import br.com.timasters.app.core.validation.DocumentValidator
import br.com.timasters.app.data.business.AddPhotoResult
import br.com.timasters.app.data.business.BusinessFiles
import br.com.timasters.app.data.business.BusinessRepository
import br.com.timasters.app.data.business.ClientEntity
import br.com.timasters.app.data.business.CompanyProfileEntity
import br.com.timasters.app.data.business.CompanyProfileInput
import br.com.timasters.app.data.business.CompanyRepository
import br.com.timasters.app.data.business.ItemKind
import br.com.timasters.app.data.business.OrderCalculator
import br.com.timasters.app.data.business.OrderInput
import br.com.timasters.app.data.business.OrderItem
import br.com.timasters.app.data.business.OrderKind
import br.com.timasters.app.data.business.OrderMediaRepository
import br.com.timasters.app.data.business.OrderPhotoEntity
import br.com.timasters.app.data.business.OrderRepository
import br.com.timasters.app.data.business.OrderStatus
import br.com.timasters.app.data.business.PhotoStage
import br.com.timasters.app.data.business.PricingInput
import br.com.timasters.app.data.business.PricingResult
import br.com.timasters.app.data.business.PricingSettingsInput
import br.com.timasters.app.data.business.ServiceItemEntity
import br.com.timasters.app.data.business.ServiceItemInput
import br.com.timasters.app.data.business.ServiceOrderWithClient
import br.com.timasters.app.data.business.StockRepository
import br.com.timasters.app.data.business.TicketPriority
import br.com.timasters.app.data.business.TicketWithClient
import br.com.timasters.app.data.business.pdf.OrderDocuments
import br.com.timasters.app.ui.screens.business.BusinessTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

private val WHILE_SUBSCRIBED_ORDERS = SharingStarted.WhileSubscribed(5_000)

// region Orders list

enum class OrderFilter { QUOTES, SERVICE_ORDERS, ALL }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OrdersViewModel @Inject constructor(
    companyRepository: CompanyRepository,
    repository: OrderRepository
) : ViewModel() {

    private val _filter = MutableStateFlow(OrderFilter.ALL)
    val filter: StateFlow<OrderFilter> = _filter.asStateFlow()
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Open documents first, newest first. */
    val orders: StateFlow<List<ServiceOrderWithClient>> = combine(
        companyRepository.companyId.filterNotNull().flatMapLatest(repository::orders),
        _filter,
        _query
    ) { all, filter, query ->
        val text = query.trim().removePrefix("#")
        all.filter {
            when (filter) {
                OrderFilter.QUOTES -> it.order.orderKind == OrderKind.QUOTE
                OrderFilter.SERVICE_ORDERS -> it.order.orderKind == OrderKind.SERVICE_ORDER
                OrderFilter.ALL -> true
            }
        }.filter {
            text.isEmpty() || it.clientName.contains(text, ignoreCase = true) || it.order.number?.toString() == text
        }.sortedWith(
            compareByDescending<ServiceOrderWithClient> { OrderStatus.from(it.order.status).isOpen }
                .thenByDescending { it.order.createdAt }
        )
    }.stateIn(viewModelScope, WHILE_SUBSCRIBED_ORDERS, emptyList())

    fun onFilterChange(value: OrderFilter) {
        _filter.value = value
    }

    fun onQueryChange(value: String) {
        _query.value = value
    }
}

// endregion

// region Order detail

/** One-off actions the screen performs (start another app, open another screen, show a message). */
sealed interface OrderEvent {
    data class Launch(val intent: Intent) : OrderEvent
    data class OpenOrder(val id: String) : OrderEvent
    data object NoWhatsApp : OrderEvent
    data object NoPhone : OrderEvent
    data object PdfFailed : OrderEvent
    data object PhotoInvalid : OrderEvent
    data object PhotoLimit : OrderEvent
}

/** A photo with its local file (null while it is still being downloaded from the cloud). */
data class OrderPhotoItem(val photo: OrderPhotoEntity, val path: String?)

enum class SendChannel { WHATSAPP_PDF, WHATSAPP_TEXT, SHARE, VIEW }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OrderDetailViewModel @Inject constructor(
    private val repository: OrderRepository,
    private val documents: OrderDocuments,
    private val media: OrderMediaRepository,
    private val files: BusinessFiles
) : ViewModel() {

    private val orderId = MutableStateFlow<String?>(null)

    val order: StateFlow<ServiceOrderWithClient?> = orderId.filterNotNull().flatMapLatest(repository::order)
        .stateIn(viewModelScope, WHILE_SUBSCRIBED_ORDERS, null)

    val photos: StateFlow<List<OrderPhotoItem>> = orderId.filterNotNull().flatMapLatest(media::photos)
        .map { list -> list.map { OrderPhotoItem(it, media.photoFile(it).takeIf(File::exists)?.path) } }
        .stateIn(viewModelScope, WHILE_SUBSCRIBED_ORDERS, emptyList())

    /** Local file of the client's signature plus the time it was saved (forces the image to reload). */
    val signature: StateFlow<Pair<String, Long>?> = order.map { current ->
        current?.order?.let { o -> media.signatureFile(o)?.let { it.path to (o.signedAt ?: 0L) } }
    }.stateIn(viewModelScope, WHILE_SUBSCRIBED_ORDERS, null)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = Channel<OrderEvent>(Channel.BUFFERED)
    val events: Flow<OrderEvent> = _events.receiveAsFlow()

    fun load(id: String) {
        orderId.value = id
    }

    fun setStatus(status: OrderStatus) {
        val id = orderId.value ?: return
        viewModelScope.launch { repository.setOrderStatus(id, status) }
    }

    fun convertToServiceOrder() {
        val id = orderId.value ?: return
        viewModelScope.launch {
            repository.convertQuoteToOrder(id)?.let { _events.send(OrderEvent.OpenOrder(it)) }
        }
    }

    /** Always makes a fresh PDF, so it matches the current data. */
    fun send(channel: SendChannel, chooserTitle: String) {
        val current = order.value ?: return
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val message = documents.shareMessage(current.order, current.clientName)
                if (channel == SendChannel.WHATSAPP_TEXT) {
                    val phone = current.clientPhone
                    if (phone == null) {
                        _events.send(OrderEvent.NoPhone)
                    } else {
                        repository.markSent(current.order.id)
                        _events.send(OrderEvent.Launch(documents.whatsAppTextIntent(phone, message)))
                    }
                    return@launch
                }
                val file = runCatching { documents.generatePdf(current.order.id) }.getOrNull()
                if (file == null) {
                    _events.send(OrderEvent.PdfFailed)
                    return@launch
                }
                val intent = when (channel) {
                    SendChannel.WHATSAPP_PDF -> documents.whatsAppPdfIntent(file, message)
                    SendChannel.SHARE -> documents.shareChooser(file, message, chooserTitle)
                    else -> documents.viewIntent(file)
                }
                if (intent == null) {
                    _events.send(OrderEvent.NoWhatsApp)
                } else {
                    if (channel != SendChannel.VIEW) repository.markSent(current.order.id)
                    _events.send(OrderEvent.Launch(intent))
                }
            } finally {
                _busy.value = false
            }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = orderId.value ?: return
        viewModelScope.launch {
            repository.deleteOrder(id)
            onDeleted()
        }
    }

    /** Where the camera app writes the photo; the screen keeps the path across a process restart. */
    fun newCameraFile(): File = files.newCameraFile()

    fun cameraUri(file: File): Uri = files.shareUri(file)

    fun addPhoto(stage: PhotoStage, source: Uri, temporaryFile: File? = null) {
        val id = orderId.value ?: return
        viewModelScope.launch {
            when (media.addPhoto(id, stage, source)) {
                AddPhotoResult.ADDED -> Unit
                AddPhotoResult.NOT_AN_IMAGE -> _events.send(OrderEvent.PhotoInvalid)
                AddPhotoResult.LIMIT_REACHED -> _events.send(OrderEvent.PhotoLimit)
            }
            temporaryFile?.delete()
        }
    }

    fun deletePhoto(photoId: String) {
        viewModelScope.launch { media.deletePhoto(photoId) }
    }

    fun saveSignature(signature: Bitmap, signer: String) {
        val id = orderId.value ?: return
        viewModelScope.launch { media.saveSignature(id, signature, signer) }
    }

    fun removeSignature() {
        val id = orderId.value ?: return
        viewModelScope.launch { media.removeSignature(id) }
    }
}

// endregion

// region Order form

/** Money and hour fields keep what was typed; they are parsed for the live total and on save. */
data class OrderForm(
    val kind: OrderKind = OrderKind.QUOTE,
    val clientId: String? = null,
    val ticketId: String? = null,
    val status: OrderStatus = OrderStatus.DRAFT,
    val items: List<OrderItem> = emptyList(),
    val laborHours: String = "",
    val hourlyRate: String = "",
    val minimumChargeCents: Long = 0,
    val useUrgency: Boolean = false,
    val urgencyPercent: String = "",
    val useTravelFee: Boolean = false,
    val travelFee: String = "",
    val discount: String = "",
    val notes: String = "",
    val validUntil: LocalDate? = null,
    val isExisting: Boolean = false,
    val showErrors: Boolean = false,
    val isLoaded: Boolean = false
) {
    val laborMinutes: Int? get() = MoneyInput.parseHoursToMinutes(laborHours)
    val hourlyRateCents: Long? get() = cents(hourlyRate)
    val urgency: Int? get() = if (!useUrgency) 0 else urgencyPercent.trim().ifEmpty { "0" }.toIntOrNull()?.takeIf { it in 0..300 }
    val travelFeeCents: Long? get() = if (!useTravelFee) 0 else cents(travelFee)
    val discountCents: Long? get() = cents(discount)

    val clientError: Boolean get() = clientId == null
    val laborError: Boolean get() = laborMinutes == null
    val hourlyRateError: Boolean get() = hourlyRateCents == null
    val urgencyError: Boolean get() = urgency == null
    val travelFeeError: Boolean get() = travelFeeCents == null
    val discountError: Boolean get() = discountCents == null
    /** Something must be charged: items or labor. */
    val emptyError: Boolean get() = items.isEmpty() && (laborMinutes ?: 0) == 0
    val isValid: Boolean
        get() = !clientError && !laborError && !hourlyRateError && !urgencyError && !travelFeeError && !discountError && !emptyError

    /** Live total while typing (invalid fields count as zero). */
    val preview: PricingResult
        get() = OrderCalculator.calculate(
            PricingInput(
                items = items,
                laborMinutes = laborMinutes ?: 0,
                hourlyRateCents = hourlyRateCents ?: 0,
                minimumChargeCents = minimumChargeCents,
                urgencyPercent = urgency ?: 0,
                travelFeeCents = travelFeeCents ?: 0,
                discountCents = discountCents ?: 0
            )
        )

    private fun cents(text: String): Long? = if (text.isBlank()) 0 else MoneyInput.parseCents(text)
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OrderEditViewModel @Inject constructor(
    private val companyRepository: CompanyRepository,
    private val repository: OrderRepository,
    private val businessRepository: BusinessRepository,
    stockRepository: StockRepository
) : ViewModel() {

    private val _form = MutableStateFlow(OrderForm())
    val form: StateFlow<OrderForm> = _form.asStateFlow()
    private var editingId: String? = null

    private val companyId = companyRepository.companyId.filterNotNull()

    val clients: StateFlow<List<ClientEntity>> = companyId.flatMapLatest(businessRepository::clients)
        .stateIn(viewModelScope, WHILE_SUBSCRIBED_ORDERS, emptyList())

    val catalog: StateFlow<List<ServiceItemEntity>> = companyId.flatMapLatest(repository::catalog)
        .map { list -> list.filter { it.active } }
        .stateIn(viewModelScope, WHILE_SUBSCRIBED_ORDERS, emptyList())

    /** Quantity in stock of each stock-controlled product, shown when picking from the catalog. */
    val stock: StateFlow<Map<String, Double>> = companyId.flatMapLatest(stockRepository::levels)
        .map { levels -> levels.associate { it.item.id to it.quantity } }
        .stateIn(viewModelScope, WHILE_SUBSCRIBED_ORDERS, emptyMap())

    val clientTickets: StateFlow<List<TicketWithClient>> = _form
        .map { it.clientId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) emptyFlow() else businessRepository.ticketsOfClient(id) }
        .stateIn(viewModelScope, WHILE_SUBSCRIBED_ORDERS, emptyList())

    /** Hours already recorded on the chosen ticket, offered as the labor time. */
    val ticketMinutes: StateFlow<Int> = _form
        .map { it.ticketId }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(0)
            } else {
                businessRepository.timeEntriesOfTicket(id).map { list ->
                    (list.sumOf { it.entry.durationMillis() } / 60_000).toInt()
                }
            }
        }
        .stateIn(viewModelScope, WHILE_SUBSCRIBED_ORDERS, 0)

    private var session: String? = null

    fun load(id: String?, kind: OrderKind, presetClientId: String?, presetTicketId: String?, session: String) {
        if (this.session == session) return
        this.session = session
        editingId = id
        _form.value = OrderForm()
        viewModelScope.launch {
            val company = companyRepository.companyId.first() ?: return@launch
            val settings = repository.pricingSettingsNow(company)
            val existing = id?.let { repository.orderNow(it) }
            _form.value = if (existing != null) {
                OrderForm(
                    kind = existing.orderKind,
                    clientId = existing.clientId,
                    ticketId = existing.ticketId,
                    status = OrderStatus.from(existing.status),
                    items = existing.items,
                    laborHours = if (existing.laborMinutes > 0) MoneyInput.minutesToInput(existing.laborMinutes) else "",
                    hourlyRate = MoneyInput.centsToInput(existing.hourlyRateCents),
                    minimumChargeCents = settings?.minimumChargeCents ?: 0,
                    useUrgency = existing.urgencyPercent > 0,
                    urgencyPercent = existing.urgencyPercent.takeIf { it > 0 }?.toString() ?: settings?.urgencyPercent?.toString().orEmpty(),
                    useTravelFee = existing.travelFeeCents > 0,
                    travelFee = MoneyInput.centsToInput(existing.travelFeeCents.takeIf { it > 0 } ?: settings?.travelFeeCents ?: 0),
                    discount = existing.discountCents.takeIf { it > 0 }?.let(MoneyInput::centsToInput).orEmpty(),
                    notes = existing.notes.orEmpty(),
                    validUntil = existing.validUntil?.let(BusinessTime::localDate),
                    isExisting = true,
                    isLoaded = true
                )
            } else {
                val ticket = presetTicketId?.let { businessRepository.ticketNow(it) }
                val ticketMinutes = presetTicketId?.let { businessRepository.ticketMinutes(it) } ?: 0
                val urgent = ticket?.priority == TicketPriority.URGENT.serverId
                OrderForm(
                    kind = kind,
                    clientId = presetClientId ?: ticket?.clientId,
                    ticketId = presetTicketId,
                    status = OrderStatus.initial(kind),
                    laborHours = if (ticketMinutes > 0) MoneyInput.minutesToInput(ticketMinutes) else "",
                    hourlyRate = MoneyInput.centsToInput(settings?.hourlyRateCents ?: 0),
                    minimumChargeCents = settings?.minimumChargeCents ?: 0,
                    useUrgency = urgent && (settings?.urgencyPercent ?: 0) > 0,
                    urgencyPercent = settings?.urgencyPercent?.toString().orEmpty(),
                    useTravelFee = (settings?.travelFeeCents ?: 0) > 0,
                    travelFee = MoneyInput.centsToInput(settings?.travelFeeCents ?: 0),
                    validUntil = if (kind == OrderKind.QUOTE) {
                        LocalDate.now(BusinessTime.zone).plusDays((settings?.quoteValidityDays ?: 15).toLong())
                    } else {
                        null
                    },
                    isLoaded = true
                )
            }
        }
    }

    fun update(transform: (OrderForm) -> OrderForm) = _form.update(transform)

    fun selectClient(client: ClientEntity) = _form.update {
        it.copy(clientId = client.id, ticketId = if (it.clientId == client.id) it.ticketId else null)
    }

    fun addItem(item: OrderItem) = _form.update { it.copy(items = it.items + item) }

    fun replaceItem(index: Int, item: OrderItem) = _form.update {
        it.copy(items = it.items.mapIndexed { i, old -> if (i == index) item else old })
    }

    fun removeItem(index: Int) = _form.update { it.copy(items = it.items.filterIndexed { i, _ -> i != index }) }

    fun useTicketHours() = _form.update { it.copy(laborHours = MoneyInput.minutesToInput(ticketMinutes.value)) }

    fun save(onSaved: (String) -> Unit) {
        val form = _form.value
        if (!form.isValid) {
            _form.update { it.copy(showErrors = true) }
            return
        }
        viewModelScope.launch {
            val company = companyRepository.companyId.first() ?: return@launch
            val id = repository.saveOrder(
                company,
                editingId,
                OrderInput(
                    kind = form.kind,
                    clientId = form.clientId!!,
                    ticketId = form.ticketId,
                    status = form.status,
                    items = form.items,
                    laborMinutes = form.laborMinutes ?: 0,
                    hourlyRateCents = form.hourlyRateCents ?: 0,
                    minimumChargeCents = form.minimumChargeCents,
                    urgencyPercent = form.urgency ?: 0,
                    travelFeeCents = form.travelFeeCents ?: 0,
                    discountCents = form.discountCents ?: 0,
                    notes = form.notes,
                    validUntil = form.validUntil?.let { BusinessTime.toMillis(it, LocalTime.of(23, 59)) }
                )
            )
            onSaved(id)
        }
    }
}

// endregion

// region Catalog

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CatalogViewModel @Inject constructor(
    companyRepository: CompanyRepository,
    repository: OrderRepository
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val items: StateFlow<List<ServiceItemEntity>> = combine(
        companyRepository.companyId.filterNotNull().flatMapLatest(repository::catalog),
        _query
    ) { all, query ->
        all.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    }.stateIn(viewModelScope, WHILE_SUBSCRIBED_ORDERS, emptyList())

    fun onQueryChange(value: String) {
        _query.value = value
    }
}

data class CatalogItemForm(
    val name: String = "",
    val description: String = "",
    val kind: ItemKind = ItemKind.SERVICE,
    val unit: String = "un",
    val price: String = "",
    val active: Boolean = true,
    val cost: String = "",
    val trackStock: Boolean = false,
    val minStock: String = "",
    val initialQuantity: String = "",
    val isExisting: Boolean = false,
    val showErrors: Boolean = false,
    val isLoaded: Boolean = false
) {
    val priceCents: Long? get() = MoneyInput.parseCents(price)
    val costCents: Long? get() = if (cost.isBlank()) 0 else MoneyInput.parseCents(cost)
    val minStockValue: Double? get() = if (minStock.isBlank()) 0.0 else MoneyInput.parseQuantity(minStock)?.takeIf { it >= 0 }
    val initialQuantityValue: Double? get() = if (initialQuantity.isBlank()) 0.0 else MoneyInput.parseQuantity(initialQuantity)?.takeIf { it >= 0 }
    val stockApplies: Boolean get() = kind == ItemKind.PRODUCT && trackStock
    val nameError: Boolean get() = name.isBlank()
    val priceError: Boolean get() = priceCents == null
    val costError: Boolean get() = costCents == null
    val minStockError: Boolean get() = stockApplies && minStockValue == null
    val initialQuantityError: Boolean get() = stockApplies && !isExisting && initialQuantityValue == null
    val isValid: Boolean get() = !nameError && !priceError && !costError && !minStockError && !initialQuantityError
}

@HiltViewModel
class CatalogItemEditViewModel @Inject constructor(
    private val companyRepository: CompanyRepository,
    private val repository: OrderRepository
) : ViewModel() {

    private val _form = MutableStateFlow(CatalogItemForm())
    val form: StateFlow<CatalogItemForm> = _form.asStateFlow()
    private var editingId: String? = null
    private var session: String? = null

    fun load(id: String?, session: String) {
        if (this.session == session) return
        this.session = session
        editingId = id
        _form.value = CatalogItemForm()
        viewModelScope.launch {
            val existing = id?.let { repository.catalogItem(it) }
            _form.value = if (existing != null) {
                CatalogItemForm(
                    name = existing.name,
                    description = existing.description.orEmpty(),
                    kind = ItemKind.from(existing.kind),
                    unit = existing.unit,
                    price = MoneyInput.centsToInput(existing.priceCents),
                    active = existing.active,
                    cost = existing.costCents.takeIf { it > 0 }?.let(MoneyInput::centsToInput).orEmpty(),
                    trackStock = existing.trackStock,
                    minStock = existing.minStock.takeIf { it > 0 }?.let(MoneyInput::formatQuantity).orEmpty(),
                    isExisting = true,
                    isLoaded = true
                )
            } else {
                CatalogItemForm(isLoaded = true)
            }
        }
    }

    fun update(transform: (CatalogItemForm) -> CatalogItemForm) = _form.update(transform)

    fun save(onSaved: () -> Unit) {
        val form = _form.value
        if (!form.isValid) {
            _form.update { it.copy(showErrors = true) }
            return
        }
        viewModelScope.launch {
            val company = companyRepository.companyId.first() ?: return@launch
            repository.saveCatalogItem(
                company,
                editingId,
                ServiceItemInput(
                    name = form.name,
                    description = form.description,
                    kind = form.kind,
                    unit = form.unit,
                    priceCents = form.priceCents!!,
                    active = form.active,
                    trackStock = form.stockApplies,
                    minStock = form.minStockValue ?: 0.0,
                    costCents = form.costCents ?: 0,
                    initialQuantity = form.initialQuantityValue ?: 0.0
                )
            )
            onSaved()
        }
    }

    fun delete(onDone: () -> Unit) {
        val id = editingId ?: return
        viewModelScope.launch {
            repository.deleteCatalogItem(id)
            onDone()
        }
    }
}

// endregion

// region Company settings (brand and prices)

data class CompanySettingsForm(
    val name: String = "",
    val document: String = "",
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val city: String = "",
    val primaryColor: String? = null,
    val hourlyRate: String = "",
    val minimumCharge: String = "",
    val travelFee: String = "",
    val urgencyPercent: String = "",
    val quoteValidityDays: String = "15",
    val paymentTerms: String = "",
    val footerNotes: String = "",
    val showErrors: Boolean = false,
    val isLoaded: Boolean = false
) {
    private fun cents(text: String): Long? = if (text.isBlank()) 0 else MoneyInput.parseCents(text)

    val nameError: Boolean get() = name.isBlank()
    val documentError: Boolean get() = document.isNotBlank() && !DocumentValidator.isValid(document)
    val emailError: Boolean get() = email.isNotBlank() && !AuthInputValidator.isValidEmail(email)
    val phoneError: Boolean get() = phone.isNotBlank() && phone.filter { it.isDigit() }.length !in 10..13
    val hourlyRateError: Boolean get() = cents(hourlyRate) == null
    val minimumChargeError: Boolean get() = cents(minimumCharge) == null
    val travelFeeError: Boolean get() = cents(travelFee) == null
    val urgencyError: Boolean get() = urgencyPercent.isNotBlank() && urgencyPercent.trim().toIntOrNull()?.takeIf { it in 0..300 } == null
    val validityError: Boolean get() = quoteValidityDays.trim().toIntOrNull()?.takeIf { it in 1..365 } == null
    val isValid: Boolean
        get() = !nameError && !documentError && !emailError && !phoneError && !hourlyRateError &&
            !minimumChargeError && !travelFeeError && !urgencyError && !validityError

    fun pricingInput() = PricingSettingsInput(
        hourlyRateCents = cents(hourlyRate) ?: 0,
        minimumChargeCents = cents(minimumCharge) ?: 0,
        travelFeeCents = cents(travelFee) ?: 0,
        urgencyPercent = urgencyPercent.trim().toIntOrNull() ?: 0,
        quoteValidityDays = quoteValidityDays.trim().toIntOrNull() ?: 15,
        paymentTerms = paymentTerms,
        footerNotes = footerNotes
    )

    fun profileInput() = CompanyProfileInput(name, document, phone, email, address, city, primaryColor)
}

sealed interface CompanySettingsState {
    data object Loading : CompanySettingsState

    /** The company row was never downloaded (first use without internet). */
    data object NotSynced : CompanySettingsState
    data object Ready : CompanySettingsState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CompanySettingsViewModel @Inject constructor(
    private val companyRepository: CompanyRepository,
    private val repository: OrderRepository,
    private val files: BusinessFiles
) : ViewModel() {

    private val _form = MutableStateFlow(CompanySettingsForm())
    val form: StateFlow<CompanySettingsForm> = _form.asStateFlow()

    private val _state = MutableStateFlow<CompanySettingsState>(CompanySettingsState.Loading)
    val state: StateFlow<CompanySettingsState> = _state.asStateFlow()

    val profile: StateFlow<CompanyProfileEntity?> = companyRepository.companyId.filterNotNull()
        .flatMapLatest(repository::companyProfile)
        .stateIn(viewModelScope, WHILE_SUBSCRIBED_ORDERS, null)

    /** Path of the local logo file plus a version so the image reloads after a change. */
    private val _logoVersion = MutableStateFlow(0)
    val logo: StateFlow<Pair<String, Int>?> = combine(profile, _logoVersion) { current, version ->
        current?.let { files.logoFile(it.id).takeIf { file -> file.exists() }?.path?.let { path -> path to version } }
    }.stateIn(viewModelScope, WHILE_SUBSCRIBED_ORDERS, null)

    private val _logoError = MutableStateFlow(false)
    val logoError: StateFlow<Boolean> = _logoError.asStateFlow()

    private var session: String? = null

    fun load(session: String) {
        if (this.session == session) return
        this.session = session
        _state.value = CompanySettingsState.Loading
        viewModelScope.launch {
            val company = companyRepository.companyId.first() ?: return@launch
            val current = repository.companyProfileNow(company)
            if (current == null) {
                _state.value = CompanySettingsState.NotSynced
                return@launch
            }
            val settings = repository.pricingSettingsNow(company)
            _form.value = CompanySettingsForm(
                name = current.name,
                document = current.document?.let(DocumentValidator::format).orEmpty(),
                phone = current.phone.orEmpty(),
                email = current.email.orEmpty(),
                address = current.address.orEmpty(),
                city = current.city.orEmpty(),
                primaryColor = current.primaryColor,
                hourlyRate = settings?.hourlyRateCents?.let(MoneyInput::centsToInput).orEmpty(),
                minimumCharge = settings?.minimumChargeCents?.let(MoneyInput::centsToInput).orEmpty(),
                travelFee = settings?.travelFeeCents?.let(MoneyInput::centsToInput).orEmpty(),
                urgencyPercent = settings?.urgencyPercent?.toString().orEmpty(),
                quoteValidityDays = (settings?.quoteValidityDays ?: 15).toString(),
                paymentTerms = settings?.paymentTerms.orEmpty(),
                footerNotes = settings?.footerNotes.orEmpty(),
                isLoaded = true
            )
            _state.value = CompanySettingsState.Ready
        }
    }

    /** Lets the screen try again after a sync brought the company row. */
    fun retry() {
        session = null
    }

    fun update(transform: (CompanySettingsForm) -> CompanySettingsForm) = _form.update(transform)

    fun pickLogo(uri: Uri) {
        viewModelScope.launch {
            val company = companyRepository.companyId.first() ?: return@launch
            _logoError.value = !repository.setLogo(company, uri)
            _logoVersion.update { it + 1 }
        }
    }

    fun removeLogo() {
        viewModelScope.launch {
            val company = companyRepository.companyId.first() ?: return@launch
            repository.removeLogo(company)
            _logoVersion.update { it + 1 }
        }
    }

    fun save(onSaved: () -> Unit) {
        val form = _form.value
        if (!form.isValid) {
            _form.update { it.copy(showErrors = true) }
            return
        }
        viewModelScope.launch {
            val company = companyRepository.companyId.first() ?: return@launch
            repository.saveCompanyProfile(company, form.profileInput())
            repository.savePricingSettings(company, form.pricingInput())
            onSaved()
        }
    }
}

// endregion
