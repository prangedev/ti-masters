package br.com.timasters.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.timasters.app.data.auth.AuthRepository
import br.com.timasters.app.data.business.BrandColorRepository
import br.com.timasters.app.ui.navigation.AppRoot
import br.com.timasters.app.ui.theme.TI_MASTERSTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    lateinit var brandColorRepository: BrandColorRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a fresh launch: after rotation the same link would be handled twice.
        if (savedInstanceState == null) authRepository.handleDeeplink(intent)

        setContent {
            val brandColor by brandColorRepository.brandColor.collectAsStateWithLifecycle(initialValue = null)
            TI_MASTERSTheme(brandColor = brandColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot()
                }
            }
        }
    }

    // launchMode="singleTask": e-mail links reach the running activity here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        authRepository.handleDeeplink(intent)
    }
}
