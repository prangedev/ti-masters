package br.com.timasters.app.ui.screens.routers

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.timasters.app.R
import br.com.timasters.app.data.routers.GuideSection
import br.com.timasters.app.data.routers.LatestRouterOs
import br.com.timasters.app.data.routers.LoginSource
import br.com.timasters.app.data.routers.RouterDetection
import br.com.timasters.app.data.routers.RouterEntry
import br.com.timasters.app.data.routers.RouterGuide
import br.com.timasters.app.data.routers.RouterLogin
import br.com.timasters.app.ui.components.AppScreenScaffold
import br.com.timasters.app.ui.components.BackIcon
import br.com.timasters.app.ui.components.FeatureGatedContent
import br.com.timasters.app.ui.components.InfoRow
import br.com.timasters.app.ui.components.LocalNetworkGate
import br.com.timasters.app.ui.components.MessageCard
import br.com.timasters.app.ui.components.SectionCard
import br.com.timasters.app.ui.viewmodel.DetectState
import br.com.timasters.app.ui.viewmodel.MyRouterViewModel
import br.com.timasters.app.ui.viewmodel.RouterCatalogViewModel
import br.com.timasters.app.ui.viewmodel.RouterGuidesViewModel
import br.com.timasters.app.ui.viewmodel.RouterModelViewModel
import kotlinx.coroutines.launch

private enum class RoutersTab(@param:StringRes val titleResId: Int) {
    MY_ROUTER(R.string.routers_tab_my_router),
    MODELS(R.string.routers_tab_models),
    CONFIGURE(R.string.routers_tab_configure),
    FIRMWARE(R.string.routers_tab_firmware),
    GUIDES(R.string.routers_tab_guides)
}

/** Screens inside Routers, kept as a list of "kind|id" strings so the stack survives rotation. */
private const val HOME = "home"
private fun modelRoute(key: String) = "model|$key"
private fun guideRoute(id: String) = "guide|$id"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutersScreen(
    canAccessRouters: Boolean,
    canAccessFiber: Boolean,
    onNavigateToPaywall: () -> Unit,
    modifier: Modifier = Modifier
) {
    var stack by rememberSaveable { mutableStateOf(listOf(HOME)) }
    var selectedTab by rememberSaveable { mutableStateOf(RoutersTab.MY_ROUTER) }
    val navigate: (String) -> Unit = { stack = stack + it }
    val back: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }
    BackHandler(enabled = stack.size > 1, onBack = back)

    val route = stack.last()
    when {
        route.startsWith("model|") -> ModelDetailScreen(
            key = route.substringAfter('|'),
            canAccessFiber = canAccessFiber,
            onNavigateToPaywall = onNavigateToPaywall,
            onOpenGuide = { navigate(guideRoute(it)) },
            onBack = back,
            modifier = modifier
        )
        route.startsWith("guide|") -> GuideDetailScreen(
            id = route.substringAfter('|'),
            canAccessFiber = canAccessFiber,
            onNavigateToPaywall = onNavigateToPaywall,
            onBack = back,
            modifier = modifier
        )
        else -> AppScreenScaffold(title = stringResource(id = R.string.routers_title), modifier = modifier) { innerPadding ->
            FeatureGatedContent(
                hasAccess = canAccessRouters,
                onNavigateToPaywall = onNavigateToPaywall,
                description = stringResource(id = R.string.feature_locked_advanced_desc),
                modifier = Modifier.padding(innerPadding)
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                    PrimaryScrollableTabRow(selectedTabIndex = selectedTab.ordinal, edgePadding = 0.dp) {
                        RoutersTab.entries.forEach { tab ->
                            Tab(
                                selected = selectedTab == tab,
                                onClick = { selectedTab = tab },
                                text = { Text(stringResource(id = tab.titleResId), maxLines = 1) }
                            )
                        }
                    }
                    when (selectedTab) {
                        RoutersTab.MY_ROUTER -> LocalNetworkGate {
                            MyRouterTab(
                                canAccessFiber = canAccessFiber,
                                onNavigateToPaywall = onNavigateToPaywall,
                                onOpenModel = { navigate(modelRoute(it)) },
                                onOpenGuide = { navigate(guideRoute(it)) },
                                onOpenModels = { selectedTab = RoutersTab.MODELS }
                            )
                        }
                        RoutersTab.MODELS -> ModelsTab(
                            canAccessFiber = canAccessFiber,
                            onNavigateToPaywall = onNavigateToPaywall,
                            onOpen = { navigate(modelRoute(it)) }
                        )
                        RoutersTab.CONFIGURE -> LocalNetworkGate { ConfigureTab() }
                        RoutersTab.FIRMWARE -> FirmwareTab(onOpenConfigure = { selectedTab = RoutersTab.CONFIGURE })
                        RoutersTab.GUIDES -> GuidesTab(
                            canAccessFiber = canAccessFiber,
                            onNavigateToPaywall = onNavigateToPaywall,
                            onOpen = { navigate(guideRoute(it)) }
                        )
                    }
                }
            }
        }
    }
}

// region My router

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MyRouterTab(
    canAccessFiber: Boolean,
    onNavigateToPaywall: () -> Unit,
    onOpenModel: (String) -> Unit,
    onOpenGuide: (String) -> Unit,
    onOpenModels: () -> Unit,
    viewModel: MyRouterViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gateway by viewModel.gateway.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            SectionCard(title = stringResource(id = R.string.routers_current_network)) {
                InfoRow(stringResource(id = R.string.routers_gateway), gateway ?: stringResource(id = R.string.routers_no_network))
                Text(
                    text = stringResource(id = R.string.routers_detect_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = viewModel::detect,
                    enabled = state != DetectState.Running && gateway != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state == DetectState.Running) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Text(stringResource(id = R.string.routers_detecting), modifier = Modifier.padding(start = 8.dp))
                    } else {
                        Icon(Icons.Default.Search, contentDescription = null)
                        Text(stringResource(id = R.string.routers_detect), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                gateway?.let { ip ->
                    OutlinedButton(onClick = { context.openUrl("http://$ip") }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                        Text(stringResource(id = R.string.routers_open_page), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        (state as? DetectState.Done)?.let { done ->
            item {
                DetectionCard(
                    detection = done.detection,
                    latest = done.latest,
                    canAccessFiber = canAccessFiber,
                    onNavigateToPaywall = onNavigateToPaywall,
                    onOpenModel = onOpenModel,
                    onOpenGuide = onOpenGuide,
                    onOpenModels = onOpenModels
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetectionCard(
    detection: RouterDetection,
    latest: LatestRouterOs?,
    canAccessFiber: Boolean,
    onNavigateToPaywall: () -> Unit,
    onOpenModel: (String) -> Unit,
    onOpenGuide: (String) -> Unit,
    onOpenModels: () -> Unit
) {
    val context = LocalContext.current
    if (!detection.foundAnything) {
        MessageCard(text = stringResource(id = R.string.routers_not_found), isError = false)
        OutlinedButton(onClick = onOpenModels, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(stringResource(id = R.string.routers_search_models))
        }
        return
    }
    val match = detection.match
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(title = stringResource(id = R.string.routers_result)) {
            InfoRow(stringResource(id = R.string.routers_brand), match?.brand?.name ?: stringResource(id = R.string.routers_unknown))
            (match?.entry?.model?.name ?: detection.modelHint)?.let { InfoRow(stringResource(id = R.string.routers_model), it) }
            InfoRow(stringResource(id = R.string.routers_firmware), detection.firmware ?: stringResource(id = R.string.routers_firmware_hidden))
            detection.mikrotik?.let { mk ->
                mk.identity?.let { InfoRow(stringResource(id = R.string.routers_identity), it) }
                mk.board?.let { InfoRow(stringResource(id = R.string.routers_board), it) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (detection.http != null) SourceChip(R.string.routers_source_page)
                if (detection.upnp != null) SourceChip(R.string.routers_source_upnp)
                if (detection.mikrotik != null) SourceChip(R.string.routers_source_mndp)
            }
            if (detection.firmware == null) {
                Text(
                    text = stringResource(id = R.string.routers_firmware_hidden_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (detection.mikrotik != null) RouterOsCard(latest, onOpenGuide)

        match?.entry?.takeIf { it.isFiber && !canAccessFiber }?.let { entry ->
            MessageCard(text = stringResource(id = R.string.routers_fiber_detected_locked, entry.model.name), isError = false)
            Button(onClick = onNavigateToPaywall, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(id = R.string.routers_see_plans))
            }
        }
        match?.entry?.takeIf { !it.isFiber || canAccessFiber }?.let { entry ->
            LoginsCard(entry)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onOpenModel(entry.key) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(id = R.string.routers_model_details), maxLines = 1)
                }
                entry.firmwareUrl?.let { url ->
                    OutlinedButton(onClick = { context.openUrl(url) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(id = R.string.routers_official_firmware), modifier = Modifier.padding(start = 6.dp), maxLines = 1)
                    }
                }
            }
        }
        if (match != null && match.entry == null) {
            MessageCard(text = stringResource(id = R.string.routers_brand_only, match.brand.name), isError = false)
            OutlinedButton(onClick = onOpenModels, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(id = R.string.routers_search_models))
            }
        }
    }
}

@Composable
private fun RouterOsCard(latest: LatestRouterOs?, onOpenGuide: (String) -> Unit) {
    SectionCard(title = stringResource(id = R.string.routers_routeros_title)) {
        when (latest) {
            null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Text(stringResource(id = R.string.routers_routeros_checking))
            }
            LatestRouterOs.Unavailable -> Text(stringResource(id = R.string.routers_routeros_unavailable))
            is LatestRouterOs.Known -> {
                InfoRow(stringResource(id = R.string.routers_routeros_installed), latest.installed)
                InfoRow(stringResource(id = R.string.routers_routeros_latest), latest.latest)
                MessageCard(
                    text = stringResource(id = if (latest.isOutdated) R.string.routers_routeros_outdated else R.string.routers_routeros_current),
                    isError = latest.isOutdated
                )
                if (latest.isOutdated) {
                    OutlinedButton(onClick = { onOpenGuide("mikrotik") }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(id = R.string.routers_how_to_update))
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceChip(@StringRes label: Int) {
    AssistChip(onClick = {}, label = { Text(stringResource(id = label)) })
}

// endregion

// region Models

/** Groups shown as chips above the model list. */
private enum class ModelFilter(@param:StringRes val labelResId: Int) {
    ALL(R.string.routers_filter_all),
    ROUTERS(R.string.routers_filter_routers),
    WIFI(R.string.routers_filter_wifi),
    FIBER(R.string.routers_filter_fiber),
    PRO(R.string.routers_filter_pro);

    fun accepts(entry: RouterEntry): Boolean = when (this) {
        ALL -> true
        ROUTERS -> !entry.isFiber && entry.model.type == "roteador"
        WIFI -> entry.model.type in setOf("repetidor", "mesh", "ap")
        FIBER -> entry.isFiber
        PRO -> entry.model.type in setOf("mikrotik", "radio")
    }
}

@Composable
private fun ModelsTab(
    canAccessFiber: Boolean,
    onNavigateToPaywall: () -> Unit,
    onOpen: (String) -> Unit,
    viewModel: RouterCatalogViewModel = hiltViewModel()
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(ModelFilter.ALL) }
    val matching = entries.filter(filter::accepts)
    val visible = matching.filter { canAccessFiber || !it.isFiber }
    val lockedCount = matching.size - visible.size

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                label = { Text(stringResource(id = R.string.routers_models_search)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                ModelFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(stringResource(id = option.labelResId)) },
                        leadingIcon = if (option == ModelFilter.FIBER && !canAccessFiber) {
                            { Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else {
                            null
                        }
                    )
                }
            }
        }
        item {
            Text(
                text = stringResource(id = R.string.routers_models_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (visible.isEmpty() && lockedCount == 0) {
            item {
                Text(
                    text = stringResource(id = R.string.routers_models_empty),
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(visible, key = { it.key }) { entry ->
            Card(
                onClick = { onOpen(entry.key) },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = listOfNotNull(entry.brand.name, entry.model.pon?.let { ponLabel(it) }).joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(entry.model.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = entry.model.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (lockedCount > 0) {
            item {
                FiberLockedCard(
                    text = stringResource(id = R.string.routers_fiber_locked_count, lockedCount),
                    onNavigateToPaywall = onNavigateToPaywall
                )
            }
        }
    }
}

@Composable
private fun FiberLockedCard(text: String, onNavigateToPaywall: () -> Unit) {
    SectionCard(title = stringResource(id = R.string.routers_fiber_locked_title)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
        }
        Button(onClick = onNavigateToPaywall, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(id = R.string.routers_see_plans))
        }
    }
}

@Composable
private fun ModelDetailScreen(
    key: String,
    canAccessFiber: Boolean,
    onNavigateToPaywall: () -> Unit,
    onOpenGuide: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RouterModelViewModel = hiltViewModel(key = "router-model-$key"),
    guidesViewModel: RouterGuidesViewModel = hiltViewModel()
) {
    LaunchedEffect(key) { viewModel.load(key) }
    val entry by viewModel.entry.collectAsStateWithLifecycle()
    val guides by guidesViewModel.guides.collectAsStateWithLifecycle()
    AppScreenScaffold(
        title = entry?.let { "${it.brand.name} · ${it.model.name}" }.orEmpty(),
        navigationIcon = { BackIcon(onBack) },
        modifier = modifier
    ) { innerPadding ->
        val current = entry ?: return@AppScreenScaffold
        FeatureGatedContent(
            hasAccess = !current.isFiber || canAccessFiber,
            onNavigateToPaywall = onNavigateToPaywall,
            description = stringResource(id = R.string.routers_fiber_locked_desc),
            modifier = Modifier.padding(innerPadding)
        ) {
            val related = current.model.guides.mapNotNull { id -> guides.firstOrNull { it.id == id } }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    SectionCard(title = current.model.name) {
                        InfoRow(stringResource(id = R.string.routers_brand), current.brand.name)
                        InfoRow(stringResource(id = R.string.routers_type), routerTypeLabel(current.model.type))
                        current.model.pon?.let { InfoRow(stringResource(id = R.string.routers_pon), ponLabel(it)) }
                        InfoRow(stringResource(id = R.string.routers_address), current.model.address)
                        current.model.notes.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                item { LoginsCard(current) }
                items(current.model.setup, key = { "setup-${it.title}" }) { section ->
                    GuideSectionCard(section, title = "${stringResource(id = R.string.routers_setup_title)}: ${section.title}")
                }
                item { FirmwareCard(current, onOpenGuide) }
                items(current.brand.recovery, key = { "recovery-${it.title}" }) { section ->
                    GuideSectionCard(section, title = "${stringResource(id = R.string.routers_recovery_title)}: ${section.title}")
                }
                if (related.isNotEmpty()) {
                    item {
                        SectionCard(title = stringResource(id = R.string.routers_related_guides)) {
                            related.forEach { guide ->
                                OutlinedButton(onClick = { onOpenGuide(guide.id) }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text(guide.title, modifier = Modifier.padding(start = 8.dp), maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FirmwareCard(entry: RouterEntry, onOpenGuide: (String) -> Unit) {
    val context = LocalContext.current
    SectionCard(title = stringResource(id = R.string.routers_firmware_title)) {
        entry.brand.notes.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        val refs = entry.firmwareRefs
        if (refs.isEmpty()) {
            Text(stringResource(id = R.string.routers_firmware_by_isp), style = MaterialTheme.typography.bodyMedium)
        }
        refs.forEachIndexed { index, ref ->
            if (index > 0) HorizontalDivider()
            Text(
                text = stringResource(id = if (ref.isRestore) R.string.routers_firmware_restore else R.string.routers_firmware_update),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(ref.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            ref.notes.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ref.url?.let { url ->
                OutlinedButton(onClick = { context.openUrl(url) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(id = R.string.routers_official_firmware), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        Button(onClick = { onOpenGuide("firmware") }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null)
            Text(stringResource(id = R.string.routers_safe_update_guide), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun LoginsCard(entry: RouterEntry) {
    SectionCard(title = stringResource(id = R.string.routers_factory_logins)) {
        entry.model.logins.forEachIndexed { index, login ->
            if (index > 0) HorizontalDivider()
            LoginRow(login)
        }
        Text(
            text = stringResource(id = R.string.routers_logins_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LoginRow(login: RouterLogin) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(login.role, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(
            text = stringResource(
                id = when (login.source) {
                    LoginSource.MANUAL -> R.string.routers_source_manual
                    LoginSource.LABEL -> R.string.routers_source_label
                    else -> R.string.routers_source_reported
                }
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        CopyLine(label = stringResource(id = R.string.routers_user), value = login.user)
        CopyLine(label = stringResource(id = R.string.routers_password), value = login.password)
    }
}

/** Value with a copy button; values in parentheses ("(em branco)") are explanations, not copied. */
@Composable
private fun CopyLine(label: String, value: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copyable = !value.startsWith("(") && !value.contains(' ')
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.35f))
        SelectionContainer(modifier = Modifier.weight(0.65f)) {
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        }
        if (copyable) {
            IconButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, value))) } }) {
                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(id = R.string.routers_copy, label))
            }
        }
    }
}

@Composable
private fun routerTypeLabel(type: String): String = stringResource(
    id = when (type) {
        "repetidor" -> R.string.routers_type_repeater
        "mesh" -> R.string.routers_type_mesh
        "ont" -> R.string.routers_type_ont
        "onu" -> R.string.routers_type_onu
        "mikrotik" -> R.string.routers_type_mikrotik
        "radio" -> R.string.routers_type_radio
        "ap" -> R.string.routers_type_ap
        else -> R.string.routers_type_router
    }
)

@Composable
private fun ponLabel(pon: String): String = stringResource(
    id = when (pon) {
        "epon" -> R.string.routers_pon_epon
        "xpon" -> R.string.routers_pon_xpon
        else -> R.string.routers_pon_gpon
    }
)

// endregion

// region Guides

@Composable
private fun GuidesTab(
    canAccessFiber: Boolean,
    onNavigateToPaywall: () -> Unit,
    onOpen: (String) -> Unit,
    viewModel: RouterGuidesViewModel = hiltViewModel()
) {
    val guides by viewModel.guides.collectAsStateWithLifecycle()
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(guides, key = { it.id }) { guide ->
            val locked = guide.fiber && !canAccessFiber
            Card(
                onClick = { if (locked) onNavigateToPaywall() else onOpen(guide.id) },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (locked) Icons.Default.Lock else Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(guide.title, style = MaterialTheme.typography.titleSmall)
                        Text(guide.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (guide.fiber) {
                        Text(
                            text = stringResource(id = R.string.routers_guide_business),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideDetailScreen(
    id: String,
    canAccessFiber: Boolean,
    onNavigateToPaywall: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RouterGuidesViewModel = hiltViewModel()
) {
    val guides by viewModel.guides.collectAsStateWithLifecycle()
    val guide: RouterGuide? = guides.firstOrNull { it.id == id }
    AppScreenScaffold(title = guide?.title.orEmpty(), navigationIcon = { BackIcon(onBack) }, modifier = modifier) { innerPadding ->
        val current = guide ?: return@AppScreenScaffold
        FeatureGatedContent(
            hasAccess = !current.fiber || canAccessFiber,
            onNavigateToPaywall = onNavigateToPaywall,
            description = stringResource(id = R.string.routers_fiber_locked_desc),
            modifier = Modifier.padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { Text(current.summary, style = MaterialTheme.typography.bodyLarge) }
                items(current.sections, key = { it.title }) { section -> GuideSectionCard(section) }
            }
        }
    }
}

/** Numbered steps, optional RouterOS commands and a tip. */
@Composable
private fun GuideSectionCard(section: GuideSection, title: String = section.title) {
    SectionCard(title = title) {
        section.steps.forEachIndexed { index, step ->
            Row {
                Text("${index + 1}.", fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                Text(step, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (section.commands.isNotEmpty()) CommandsBlock(section.commands)
        section.tip?.let { MessageCard(text = it, isError = false) }
    }
}

/** RouterOS commands in a monospace box with one button that copies them all. */
@Composable
private fun CommandsBlock(commands: List<String>) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val label = stringResource(id = R.string.routers_commands)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            SelectionContainer {
                Text(
                    text = commands.joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        OutlinedButton(
            onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, commands.joinToString("\n")))) } },
            modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(stringResource(id = R.string.routers_copy_commands), modifier = Modifier.padding(start = 6.dp))
        }
    }
}

// endregion

private fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        // No browser installed; nothing to do.
    }
}
