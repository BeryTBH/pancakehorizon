package com.veygax.eventhorizon.ui.activities

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.veygax.eventhorizon.system.DomainBlockerManager
import com.veygax.eventhorizon.utils.RootUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DomainBlockerActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val useDarkTheme = isSystemInDarkTheme()
            val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val ctx = LocalContext.current
                if (useDarkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            } else {
                if (useDarkTheme) darkColorScheme() else lightColorScheme()
            }

            MaterialTheme(colorScheme = colorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DomainBlockerScreen(onNavigateBack = { finish() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DomainBlockerScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("eventhorizon_prefs", Context.MODE_PRIVATE) }
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val isUnlockedBootloader = remember { sharedPrefs.getBoolean("is_unlocked_bootloader", false) }

    var isMasterEnabled by remember {
        mutableStateOf(sharedPrefs.getBoolean("root_blocker_is_running", false))
    }

    LaunchedEffect(Unit) {
        val actualState = withContext(Dispatchers.IO) {
            DomainBlockerManager.checkIsEnabled(context)
        }
        if (actualState != isMasterEnabled) {
            isMasterEnabled = actualState
            sharedPrefs.edit().putBoolean("root_blocker_is_running", actualState).apply()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Domain Blocker") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Master Toggle Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isMasterEnabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Master Switch", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = if (isMasterEnabled) "Blocker is Active" else "Blocker is Disabled",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (isUnlockedBootloader) {
                            Text(
                                text = "Toggling this will perform a reboot.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Switch(
                        checked = isMasterEnabled,
                        onCheckedChange = { isEnabled ->
                            isMasterEnabled = isEnabled
                            sharedPrefs.edit().putBoolean("root_blocker_is_running", isEnabled).apply()

                            coroutineScope.launch {
                                if (isEnabled) {
                                    val success = DomainBlockerManager.generateAndApplyHosts(context)
                                    withContext(Dispatchers.Main) {
                                        if (success) {
                                            if (isUnlockedBootloader) {
                                                sharedPrefs.edit().putBoolean("cycle_wifi_on_boot", true).apply()
                                                snackbarHostState.showSnackbar("Module installed — rebooting device")
                                                withContext(Dispatchers.IO) {
                                                    delay(500)
                                                    RootUtils.runAsRoot("reboot")
                                                }
                                            } else {
                                                snackbarHostState.showSnackbar("Blocker enabled & hosts applied")
                                            }
                                        } else {
                                            snackbarHostState.showSnackbar("Failed to apply hosts file")
                                        }
                                    }
                                } else {
                                    val success = DomainBlockerManager.disableRootBlocker(context)
                                    withContext(Dispatchers.Main) {
                                        if (success) {
                                            if (isUnlockedBootloader) {
                                                snackbarHostState.showSnackbar("Module disabled — rebooting device")
                                                withContext(Dispatchers.IO) {
                                                    delay(500)
                                                    RootUtils.runAsRoot("reboot")
                                                }
                                            } else {
                                                snackbarHostState.showSnackbar("Blocker disabled")
                                            }
                                        } else {
                                            snackbarHostState.showSnackbar("Failed to disable blocker")
                                        }
                                    }
                                }
                            }
                        }
                    )
                }
            }

            HorizontalDivider()

            // Categories List
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(DomainBlockerManager.categories.keys.toList()) { categoryName ->
                    val prefKey = DomainBlockerManager.getPrefKey(categoryName)
                    var isCategoryEnabled by remember { mutableStateOf(sharedPrefs.getBoolean(prefKey, false)) }

                    val isHighRisk = categoryName.contains("Auth / Account")

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isHighRisk) MaterialTheme.colorScheme.errorContainer
                                            else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = categoryName,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isHighRisk) MaterialTheme.colorScheme.onErrorContainer
                                            else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Blocks ${DomainBlockerManager.categories[categoryName]?.size ?: 0} domains",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isHighRisk) MaterialTheme.colorScheme.onErrorContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (isHighRisk) {
                                    Text(
                                        text = "HIGH RISK: Can break login and store access.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Switch(
                                checked = isCategoryEnabled,
                                onCheckedChange = { checked ->
                                    isCategoryEnabled = checked
                                    sharedPrefs.edit().putBoolean(prefKey, checked).apply()

                                    if (isMasterEnabled) {
                                        coroutineScope.launch {
                                            DomainBlockerManager.generateAndApplyHosts(context)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Domain Blocker Screen Preview")
@Composable
fun DomainBlockerScreenPreview() {
    MaterialTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            DomainBlockerScreen(onNavigateBack = {})
        }
    }
}