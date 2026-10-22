package br.com.timasters.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.timasters.app.BuildConfig
import br.com.timasters.app.data.plan.Entitlement
import br.com.timasters.app.data.plan.EntitlementRepository
import br.com.timasters.app.data.plan.SubscriptionPlan
import br.com.timasters.app.data.preferences.AppPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class PlanRefreshState { IDLE, REFRESHING, FAILED }

@HiltViewModel
class MainViewModel @Inject constructor(
    private val entitlementRepository: EntitlementRepository,
    private val preferences: AppPreferencesRepository
) : ViewModel() {

    /** Null while loading, so the tour question never flashes for users who already answered it. */
    val tourCompleted: StateFlow<Boolean?> = preferences.tourCompleted
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun completeTour() {
        viewModelScope.launch { preferences.setTourCompleted(true) }
    }

    /** Null until the cached plan is loaded, so paid users never see an ad flash. */
    val entitlement: StateFlow<Entitlement?> = entitlementRepository.entitlement.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    private val _refreshState = MutableStateFlow(PlanRefreshState.IDLE)
    val refreshState: StateFlow<PlanRefreshState> = _refreshState.asStateFlow()

    /** Simulating plans without payment is a developer tool only. */
    val canSimulatePlan: Boolean = BuildConfig.DEBUG

    /** Confirms the plan with the server. Offline, the cached plan keeps working. */
    fun refreshEntitlement() {
        if (_refreshState.value == PlanRefreshState.REFRESHING) return
        _refreshState.value = PlanRefreshState.REFRESHING
        viewModelScope.launch {
            _refreshState.value = try {
                entitlementRepository.refresh()
                PlanRefreshState.IDLE
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                PlanRefreshState.FAILED
            }
        }
    }

    fun simulatePlan(plan: SubscriptionPlan?) {
        if (!canSimulatePlan) return
        viewModelScope.launch { entitlementRepository.setSimulatedPlan(plan) }
    }
}
