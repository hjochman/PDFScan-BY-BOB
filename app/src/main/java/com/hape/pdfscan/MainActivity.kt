package com.hape.pdfscan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.hape.pdfscan.navigation.AppNavHost
import com.hape.pdfscan.navigation.NavRoutes
import com.hape.pdfscan.storage.appDataStore
import com.hape.pdfscan.ui.theme.PDFScanByBobTheme
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

private val KEY_LAST_SEEN_VERSION = stringPreferencesKey("about_last_seen_version")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PDFScanByBobTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()

                    // On first composition, check if About needs to be shown automatically
                    LaunchedEffect(Unit) {
                        val prefs = applicationContext.appDataStore.data.first()
                        val lastSeen = prefs[KEY_LAST_SEEN_VERSION] ?: ""
                        if (lastSeen != BuildConfig.VERSION_NAME) {
                            navController.navigate(NavRoutes.ABOUT)
                        }
                    }

                    AppNavHost(navController = navController)
                }
            }
        }
    }
}
