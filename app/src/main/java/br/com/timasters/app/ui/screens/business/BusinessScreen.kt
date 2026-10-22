package br.com.timasters.app.ui.screens.business

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.timasters.app.R
import br.com.timasters.app.data.business.sync.SyncError
import br.com.timasters.app.ui.components.AppScreenScaffold
import br.com.timasters.app.ui.components.FeatureGatedContent
import br.com.timasters.app.ui.viewmodel.BusinessHomeViewModel
import br.com.timasters.app.ui.viewmodel.CompanyState
import br.com.timasters.app.ui.viewmodel.SyncInfo

/** [addResId] is the floating button of the tab (null: no button). */
private enum class BusinessTab(@param:StringRes val titleResId: Int, @param:StringRes val addResId: Int?) {
    DASHBOARD(R.string.business_tab_dashboard, null),
    TICKETS(R.string.business_tab_tickets, R.string.ticket_new),
    CLIENTS(R.string.business_tab_clients, R.string.client_new),
    AGENDA(R.string.business_tab_agenda, R.string.appointment_new),
    HOURS(R.string.business_tab_hours, R.string.time_entry_new),
    ORDERS(R.string.business_tab_orders, R.string.order_new_quote)
}

@Composable
fun BusinessScreen(
    canAccessCrm: Boolean,
    onNavigateToPaywall: () -> Unit,
    modifier: Modifier = Modifier
) {
    FeatureGatedContent(
        hasAccess = canAccessCrm,
        onNavigateToPaywall = onNavigateToPaywall,
        description = stringResource(id = R.string.feature_locked_business_desc),
        modifier = modifier
    ) {
        BusinessNavHost(modifier = modifier)
    }
}

@Composable
private fun BusinessNavHost(modifier: Modifier = Modifier) {
    var stack by rememberSaveable(stateSaver = BusinessBackStackSaver) { mutableStateOf(listOf<BusinessRoute>(BusinessRoute.Home)) }
    // Kept here (always composed) so returning from a detail screen shows the same tab.
    var selectedTab by rememberSaveable { mutableStateOf(BusinessTab.DASHBOARD) }
    val navigate: (BusinessRoute) -> Unit = { stack = stack + it }
    val back: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }
    /** Leaves an edit screen and shows the saved record instead (no way "back" into the form). */
    val replace: (BusinessRoute) -> Unit = { stack = stack.dropLast(1) + it }

    BackHandler(enabled = stack.size > 1, onBack = back)

    when (val route = stack.last()) {
        BusinessRoute.Home -> BusinessHome(
            selectedTab = selectedTab,
            onSelectTab = { selectedTab = it },
            navigate = navigate,
            modifier = modifier
        )
        is BusinessRoute.ClientDetail -> ClientDetailScreen(route.id, navigate, back, modifier)
        is BusinessRoute.ClientEdit -> ClientEditScreen(
            clientId = route.id,
            session = route.session,
            onSaved = { id -> if (route.id == null) replace(BusinessRoute.ClientDetail(id)) else back() },
            onBack = back,
            modifier = modifier
        )
        is BusinessRoute.TicketDetail -> TicketDetailScreen(route.id, navigate, back, modifier)
        is BusinessRoute.TicketEdit -> TicketEditScreen(
            ticketId = route.id,
            presetClientId = route.clientId,
            session = route.session,
            onSaved = { id -> if (route.id == null) replace(BusinessRoute.TicketDetail(id)) else back() },
            onBack = back,
            modifier = modifier
        )
        is BusinessRoute.AppointmentEdit -> AppointmentEditScreen(route.id, route.clientId, route.ticketId, route.session, back, modifier)
        is BusinessRoute.TimeEntryEdit -> TimeEntryEditScreen(route.id, route.session, back, modifier)
        is BusinessRoute.OrderDetail -> OrderDetailScreen(
            orderId = route.id,
            navigate = navigate,
            onOpenOther = { id -> replace(BusinessRoute.OrderDetail(id)) },
            onBack = back,
            modifier = modifier
        )
        is BusinessRoute.OrderEdit -> OrderEditScreen(
            route = route,
            onSaved = { id -> if (route.id == null) replace(BusinessRoute.OrderDetail(id)) else back() },
            onOpenCatalog = { navigate(BusinessRoute.Catalog) },
            onBack = back,
            modifier = modifier
        )
        is BusinessRoute.CompanySettings -> CompanySettingsScreen(
            session = route.session,
            onOpenCatalog = { navigate(BusinessRoute.Catalog) },
            onBack = back,
            modifier = modifier
        )
        BusinessRoute.Catalog -> CatalogScreen(
            onOpenItem = { id -> navigate(BusinessRoute.CatalogItemEdit(id)) },
            onOpenStock = { navigate(BusinessRoute.Stock) },
            onBack = back,
            modifier = modifier
        )
        BusinessRoute.Stock -> StockScreen(
            onOpenItem = { id -> navigate(BusinessRoute.StockItem(id)) },
            onOpenCatalog = { navigate(BusinessRoute.Catalog) },
            onBack = back,
            modifier = modifier
        )
        is BusinessRoute.StockItem -> StockItemScreen(
            itemId = route.id,
            onEditItem = { navigate(BusinessRoute.CatalogItemEdit(route.id)) },
            onOpenOrder = { id -> navigate(BusinessRoute.OrderDetail(id)) },
            onBack = back,
            modifier = modifier
        )
        is BusinessRoute.CatalogItemEdit -> CatalogItemEditScreen(route.id, route.session, back, modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BusinessHome(
    selectedTab: BusinessTab,
    onSelectTab: (BusinessTab) -> Unit,
    navigate: (BusinessRoute) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BusinessHomeViewModel = hiltViewModel()
) {
    val companyState by viewModel.companyState.collectAsStateWithLifecycle()
    val syncInfo by viewModel.syncInfo.collectAsStateWithLifecycle()
    AppScreenScaffold(
        title = stringResource(id = R.string.nav_business),
        actions = {
            SyncButton(syncInfo, onClick = viewModel::syncNow)
            if (companyState is CompanyState.Ready) {
                IconButton(onClick = { navigate(BusinessRoute.CompanySettings()) }) {
                    Icon(Icons.Default.Storefront, contentDescription = stringResource(id = R.string.company_settings_title))
                }
            }
        },
        floatingActionButton = {
            val addResId = selectedTab.addResId
            if (companyState is CompanyState.Ready && addResId != null) {
                ExtendedFloatingActionButton(
                    onClick = {
                        when (selectedTab) {
                            BusinessTab.DASHBOARD -> null
                            BusinessTab.TICKETS -> BusinessRoute.TicketEdit(null)
                            BusinessTab.CLIENTS -> BusinessRoute.ClientEdit(null)
                            BusinessTab.AGENDA -> BusinessRoute.AppointmentEdit(null)
                            BusinessTab.HOURS -> BusinessRoute.TimeEntryEdit(null)
                            BusinessTab.ORDERS -> BusinessRoute.OrderEdit(null)
                        }?.let(navigate)
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(id = addResId)) }
                )
            }
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (companyState) {
                CompanyState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                CompanyState.Unavailable -> CompanyUnavailable(onRetry = viewModel::loadCompany)
                is CompanyState.Ready -> {
                    PrimaryScrollableTabRow(selectedTabIndex = selectedTab.ordinal, edgePadding = 8.dp) {
                        BusinessTab.entries.forEach { tab ->
                            Tab(
                                selected = selectedTab == tab,
                                onClick = { onSelectTab(tab) },
                                text = { Text(stringResource(id = tab.titleResId), maxLines = 1) }
                            )
                        }
                    }
                    SyncStatusLine(syncInfo)
                    when (selectedTab) {
                        BusinessTab.DASHBOARD -> DashboardTab(onOpenStock = { navigate(BusinessRoute.Stock) })
                        BusinessTab.TICKETS -> TicketsTab(onOpen = { navigate(BusinessRoute.TicketDetail(it)) })
                        BusinessTab.CLIENTS -> ClientsTab(onOpen = { navigate(BusinessRoute.ClientDetail(it)) })
                        BusinessTab.AGENDA -> AgendaTab(onOpen = { navigate(BusinessRoute.AppointmentEdit(it)) })
                        BusinessTab.HOURS -> HoursTab(onOpen = { navigate(BusinessRoute.TimeEntryEdit(it)) })
                        BusinessTab.ORDERS -> OrdersTab(onOpen = { navigate(BusinessRoute.OrderDetail(it)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncButton(info: SyncInfo, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = !info.isSyncing) {
        if (info.isSyncing) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        } else {
            Icon(
                imageVector = when {
                    info.error == SyncError.OFFLINE -> Icons.Default.CloudOff
                    info.error != null -> Icons.Default.SyncProblem
                    info.pendingChanges > 0 -> Icons.Default.CloudUpload
                    info.lastSyncAt != null -> Icons.Default.CloudDone
                    else -> Icons.Default.Cloud
                },
                contentDescription = stringResource(id = R.string.sync_now),
                tint = if (info.error != null && info.error != SyncError.OFFLINE) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}

/** One line under the tabs telling whether everything is saved in the cloud. */
@Composable
private fun SyncStatusLine(info: SyncInfo) {
    val text = when {
        info.isSyncing -> stringResource(id = R.string.sync_in_progress)
        info.error != null && info.pendingChanges > 0 ->
            stringResource(id = R.string.sync_pending_with_error, info.pendingChanges, stringResource(id = info.error.messageResId))
        info.error != null -> stringResource(id = info.error.messageResId)
        info.pendingChanges > 0 -> stringResource(id = R.string.sync_pending, info.pendingChanges)
        info.lastSyncAt != null -> stringResource(id = R.string.sync_done_at, BusinessTime.formatDateTime(info.lastSyncAt))
        else -> null
    } ?: return
    Surface(
        color = if (info.error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (info.error != null) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
}

@Composable
private fun CompanyUnavailable(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(48.dp))
        Text(
            text = stringResource(id = R.string.business_first_load_offline),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 16.dp)
        )
        Button(onClick = onRetry) { Text(stringResource(id = R.string.retry)) }
    }
}

/** Empty-list message used by all Business lists. */
@Composable
internal fun EmptyMessage(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
