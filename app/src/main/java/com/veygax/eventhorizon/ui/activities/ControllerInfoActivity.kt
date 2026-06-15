package com.veygax.eventhorizon.ui.activities

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.veygax.eventhorizon.utils.RootUtils
import kotlinx.coroutines.launch

interface ControllerActions {
    suspend fun loadControllerInfo(): String
    suspend fun runControllerCommand(command: String): String
}

class ControllerInfoActivity : ComponentActivity(), ControllerActions {
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
                    ControllerInfoScreen(actions = this, onBack = { finish() })
                }
            }
        }
    }

    override suspend fun loadControllerInfo(): String {
        return RootUtils.runAsRoot("rstest info")
    }

    override suspend fun runControllerCommand(command: String): String {
        return RootUtils.runAsRoot(command)
    }
}

data class ControllerDevice(
    val typeId: String,
    val typeName: String,
    val model: String? = null,
    val id: String? = null,
    val serial: String? = null,
    val status: String = "UNKNOWN",
    val battery: String? = null,
    val firmware: String? = null,
    val isPaired: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControllerInfoScreen(
    actions: ControllerActions,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var controllers by remember { mutableStateOf<List<ControllerDevice>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    fun refreshData() {
        coroutineScope.launch {
            isLoading = true
            val output = actions.loadControllerInfo()
            controllers = parseRstestOutput(output)
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshData()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Controller Management") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { refreshData() }, enabled = !isLoading) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(controllers) { controller ->
                        ControllerCard(
                            controller = controller,
                            onPair = { alias ->
                                coroutineScope.launch {
                                    isLoading = true
                                    Toast.makeText(context, "Scanning and pairing $alias (15s timeout)...", Toast.LENGTH_SHORT).show()
                                    actions.runControllerCommand("rstest scanAndPair $alias 15000")
                                    refreshData()
                                }
                            },
                            onUnpair = { alias ->
                                coroutineScope.launch {
                                    isLoading = true
                                    actions.runControllerCommand("rstest unpair $alias")
                                    refreshData()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ControllerCard(
    controller: ControllerDevice,
    onPair: (String) -> Unit,
    onUnpair: (String) -> Unit
) {
    val alias = when {
        controller.typeName.contains("LeftHand", ignoreCase = true) -> "left"
        controller.typeName.contains("RightHand", ignoreCase = true) -> "right"
        else -> controller.typeId
    }

    val isActionableAlias = alias == "left" || alias == "right"

    val statusColor = when (controller.status) {
        "CONNECTED_ACTIVE" -> Color(0xFF4CAF50)
        "CONNECTED_INACTIVE" -> Color(0xFF2196F3)
        "SEARCHING" -> Color(0xFFFF9800)
        "NOT PAIRED" -> Color.Gray
        else -> MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = controller.typeName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = controller.status,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = statusColor
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (controller.isPaired) {
                controller.model?.let { Text("Model: $it", style = MaterialTheme.typography.bodyMedium) }
                controller.id?.let { Text("ID: $it", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                controller.serial?.let { Text("Serial: $it", style = MaterialTheme.typography.bodyMedium) }
                controller.firmware?.let { Text("Firmware: $it", style = MaterialTheme.typography.bodyMedium) }
                controller.battery?.let { Text("Battery: $it%", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold) }
                
                Spacer(modifier = Modifier.height(12.dp))
                if (isActionableAlias) {
                    Button(
                        onClick = { onUnpair(alias) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Unpair Controller")
                    }
                }
            } else {
                Text("No device paired to this slot.", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                Spacer(modifier = Modifier.height(12.dp))
                if (isActionableAlias) {
                    Button(
                        onClick = { onPair(alias) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Scan & Pair")
                    }
                }
            }
        }
    }
}

fun parseRstestOutput(output: String): List<ControllerDevice> {
    val devices = mutableListOf<ControllerDevice>()
    val lines = output.split("\n")

    for (line in lines) {
        if (!line.contains("Paired device:")) continue

        if (line.contains("NoHand") || line.contains("NoHandSecond")) continue

        if (line.contains("<none>")) {
            val typeId = Regex("""deviceType:\s*(\d+)""").find(line)?.groupValues?.get(1) ?: ""
            val typeName = Regex("""\(([^)]+)\)""").find(line)?.groupValues?.get(1) ?: "Unknown Slot"
            devices.add(
                ControllerDevice(
                    typeId = typeId,
                    typeName = typeName,
                    status = "NOT PAIRED",
                    isPaired = false
                )
            )
        } else {
            val typeId = Regex("""deviceType:\s*(\d+)""").find(line)?.groupValues?.get(1) ?: ""
            val typeName = Regex("""\((LeftHand|RightHand)\)""").find(line)?.groupValues?.get(1)
                ?: Regex("""deviceType:\s*\d+\s+\(([^)]+)\)""").find(line)?.groupValues?.get(1)
                ?: "Unknown Slot"

            val modelName = Regex("""model:\s*\d+\s+\(([^)]+)\)""").find(line)?.groupValues?.get(1)
                ?: Regex("""model:\s*(\d+)""").find(line)?.groupValues?.get(1)

            val id = Regex("""id:\s*([a-f0-9A-F]+)""").find(line)?.groupValues?.get(1)
            val status = Regex("""status:\s*([A-Z_]+)""").find(line)?.groupValues?.get(1) ?: "PAIRED"
            val bat = Regex("""bat:\s*(\d+)""").find(line)?.groupValues?.get(1)
            val fw = Regex("""fw:\s*([0-9.]+)""").find(line)?.groupValues?.get(1)
            
            var serial: String? = null
            val serialSegment = line.substringAfter("serial:", "")
            if (serialSegment.isNotEmpty()) {
                val candidate = serialSegment.substringBefore("status:").trim()
                if (candidate.isNotEmpty()) {
                    serial = candidate
                }
            }

            devices.add(
                ControllerDevice(
                    typeId = typeId,
                    typeName = typeName,
                    model = modelName,
                    id = id,
                    serial = serial ?: "N/A",
                    status = status,
                    battery = bat,
                    firmware = fw ?: "N/A",
                    isPaired = true
                )
            )
        }
    }
    return devices
}

@Preview(showBackground = true, name = "Controllers Management Preview")
@Composable
fun ControllerInfoScreenPreview() {
    val mockActions = object : ControllerActions {
        override suspend fun loadControllerInfo(): String {
            return """
                Service supports deviceType: 1  (LeftHand)
                Service supports deviceType: 0  (RightHand)
                Paired device: model: 5  (RUBY)  deviceType: 1  (LeftHand)  id: dbda3f9d2641da30  serial: 2G0YYH1F8802HV   status: CONNECTED_ACTIVE  trackingStatus: NONE  brightnessLevel: GOOD  hardwarRev: 0x10  fw: 85.35.82  imuModel: ICM42686  bat: 60  lastConn: 643.154
                Paired device: model: 5  (RUBY)  deviceType: 0  (RightHand)  id: a4bbdd6f3e5b5ec6  serial:   status: SEARCHING  trackingStatus: NONE  brightnessLevel: GOOD  hardwarRev:   fw:   imuModel:   bat: 10  lastConn: -1.000
            """.trimIndent()
        }

        override suspend fun runControllerCommand(command: String): String = "Command executed successfully"
    }

    MaterialTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            ControllerInfoScreen(actions = mockActions, onBack = {})
        }
    }
}