package br.com.timasters.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.timasters.app.core.format.MoneyInput
import br.com.timasters.app.data.business.BusinessDashboard
import br.com.timasters.app.data.business.BusinessRepository
import br.com.timasters.app.data.business.CompanyRepository
import br.com.timasters.app.data.business.DashboardSummary
import br.com.timasters.app.data.business.OrderRepository
import br.com.timasters.app.data.business.StockLevel
import br.com.timasters.app.data.business.StockMovementKind
import br.com.timasters.app.data.business.StockMovementWithOrder
import br.com.timasters.app.data.business.StockRepository
import br.com.timasters.app.ui.screens.business.BusinessTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.YearMonth
import javax.inject.Inject

private val WHILE_SUBSCRIBED_PART3 = SharingStarted.WhileSubscribed(5_000)

// region Dashboard

data class DashboardState(
    val summary: DashboardSummary? = null,
    val lowStock: List<StockLevel> = emptyList(),
    val isCurrentMonth: Boolean = true
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    companyRepository: CompanyRepository,
    orderRepository: OrderRepository,
    businessRepository: BusinessRepository,
    stockRepository: StockRepository
) : ViewModel() {

    private val _month = MutableStateFlow(YearMonth.now(BusinessTime.zone))

    val state: StateFlow<DashboardState> = companyRepository.companyId.filterNotNull().flatMapLatest { company ->
        combine(
            orderRepository.orders(company),
            businessRepository.timeEntries(company),
            stockRepository.levels(company),
            _month
        ) { orders, entries, levels, month ->
            DashboardState(
                summary = BusinessDashboard.summarize(orders, entries.map { it.entry }, month, BusinessTime.zone),
                lowStock = levels.filter { it.isLow },
                isCurrentMonth = month == YearMonth.now(BusinessTime.zone)
            )
        }
    }.stateIn(viewModelScope, WHILE_SUBSCRIBED_PART3, DashboardState())

    fun previousMonth() = _month.update { it.minusMonths(1) }

    fun nextMonth() = _month.update { current ->
        if (current < YearMonth.now(BusinessTime.zone)) current.plusMonths(1) else current
    }
}

// endregion

// region Stock

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StockViewModel @Inject constructor(
    companyRepository: CompanyRepository,
    stockRepository: StockRepository
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _onlyLow = MutableStateFlow(false)
    val onlyLow: StateFlow<Boolean> = _onlyLow.asStateFlow()

    /** Low items first, then by name. */
    val levels: StateFlow<List<StockLevel>> = combine(
        companyRepository.companyId.filterNotNull().flatMapLatest(stockRepository::levels),
        _query,
        _onlyLow
    ) { all, query, onlyLow ->
        all.filter { query.isBlank() || it.item.name.contains(query.trim(), ignoreCase = true) }
            .filter { !onlyLow || it.isLow }
            .sortedWith(compareByDescending<StockLevel> { it.isLow }.thenBy { it.item.name.lowercase() })
    }.stateIn(viewModelScope, WHILE_SUBSCRIBED_PART3, emptyList())

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun onOnlyLowChange(value: Boolean) {
        _onlyLow.value = value
    }
}

/** Entry, withdrawal or count typed in the stock dialog. */
enum class StockAction { IN, OUT, COUNT }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StockItemViewModel @Inject constructor(
    private val stockRepository: StockRepository
) : ViewModel() {

    private val itemId = MutableStateFlow<String?>(null)

    val level: StateFlow<StockLevel?> = itemId.filterNotNull().flatMapLatest(stockRepository::level)
        .stateIn(viewModelScope, WHILE_SUBSCRIBED_PART3, null)

    val history: StateFlow<List<StockMovementWithOrder>> = itemId.filterNotNull().flatMapLatest(stockRepository::history)
        .stateIn(viewModelScope, WHILE_SUBSCRIBED_PART3, emptyList())

    val defaultCost: StateFlow<String> = level.map { current ->
        current?.item?.costCents?.takeIf { it > 0 }?.let(MoneyInput::centsToInput).orEmpty()
    }.stateIn(viewModelScope, WHILE_SUBSCRIBED_PART3, "")

    fun load(id: String) {
        itemId.value = id
    }

    /**
     * Returns false when the quantity is invalid. [cost] is only used for entries (cost of
     * this purchase, informative); counts set the quantity on the shelf.
     */
    fun apply(action: StockAction, quantityText: String, cost: String, note: String): Boolean {
        val id = itemId.value ?: return false
        val quantity = MoneyInput.parseQuantity(quantityText) ?: return false
        if (quantity < 0 || (action != StockAction.COUNT && quantity == 0.0)) return false
        val costCents = if (cost.isBlank()) 0 else MoneyInput.parseCents(cost) ?: return false
        viewModelScope.launch {
            when (action) {
                StockAction.IN -> stockRepository.addMovement(id, StockMovementKind.IN, quantity, costCents, note)
                StockAction.OUT -> stockRepository.addMovement(id, StockMovementKind.OUT, -quantity, 0, note)
                StockAction.COUNT -> stockRepository.adjustTo(id, quantity, note)
            }
        }
        return true
    }

    fun undo(movementId: String) {
        viewModelScope.launch { stockRepository.deleteMovement(movementId) }
    }
}

// endregion
