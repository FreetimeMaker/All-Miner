package com.freetime.allminer

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.*
import kotlin.random.Random
import com.freetime.allminer.ui.theme.AllMinerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AllMinerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MinerScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

class MinerViewModel(application: Application) : AndroidViewModel(application) {
    init {
        System.loadLibrary("allminer")
    }

    private external fun initRandomX(key: String, args: String): Boolean
    private external fun prepareThreads(count: Int)
    private external fun performRandomXHash(threadId: Int, input: ByteArray): Long
    private external fun getEngineVersion(): String

    var isMining by mutableStateOf(false)
    var isInitializing by mutableStateOf(false)
    var hashrate by mutableDoubleStateOf(0.0)
    var walletAddress by mutableStateOf("")
    var poolUrl by mutableStateOf("rx.unmineable.com:3333")
    var startArgs by mutableStateOf("--cpu-max-threads-hint 50")
    var totalHashes by mutableLongStateOf(0L)
    var selectedCoin by mutableStateOf("DOGE")
    var engineInfo by mutableStateOf(getEngineVersion())
    var numThreads by mutableIntStateOf(1)
    
    // Akku- und Hitzeschutz Status
    var batteryLevel by mutableIntStateOf(100)
    var isBatteryLow by mutableStateOf(false)
    var isDeviceHot by mutableStateOf(false)
    var stopReason by mutableStateOf("")

    private var miningJobs = mutableListOf<Job>()
    private var monitoringJob: Job? = null

    init {
        startMonitoring()
    }

    private fun startMonitoring() {
        monitoringJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                updateBatteryStatus()
                updateThermalStatus()
                
                if (isMining && (isBatteryLow || isDeviceHot)) {
                    stopReason = if (isDeviceHot) "Device too hot!" else "Battery too low (< 20%)"
                    withContext(Dispatchers.Main) {
                        stopMining()
                    }
                }
                delay(5000) // Alle 5 Sekunden prüfen
            }
        }
    }

    private fun updateBatteryStatus() {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = getApplication<Application>().registerReceiver(null, intentFilter)
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        batteryLevel = if (scale > 0) (level * 100 / scale.toFloat()).toInt() else 0
        
        // Schutz ab 20% Akku
        isBatteryLow = batteryLevel < 20
    }

    private fun updateThermalStatus() {
        val powerManager = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as PowerManager
        // Thermal Status ab Android 10 (API 29)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val status = powerManager.currentThermalStatus
            isDeviceHot = status >= PowerManager.THERMAL_STATUS_MODERATE
        }
    }

    val coins = listOf("DOGE", "SHIB", "PEPE", "SOL", "ADA", "XRP", "LTC", "BONK", "FLOKI")

    fun toggleMining() {
        if (isMining) {
            stopMining()
        } else {
            stopReason = ""
            startMining()
        }
    }

    private fun startMining() {
        if (isBatteryLow || isDeviceHot) {
            stopReason = if (isDeviceHot) "Device too hot!" else "Battery too low"
            return
        }

        val unmineableAddress = "$selectedCoin:$walletAddress.AllMinerApp"
        
        isInitializing = true
        viewModelScope.launch(Dispatchers.Default) {
            val success = initRandomX("unmineable_rx_seed", startArgs)
            if (success) {
                prepareThreads(numThreads)
                isInitializing = false
                isMining = true
                
                repeat(numThreads) { threadId ->
                    miningJobs.add(launch {
                        while (isActive) {
                            val startTime = System.currentTimeMillis()
                            var batchHashes = 0L
                            for (i in 1..10) {
                                batchHashes += performRandomXHash(threadId, unmineableAddress.toByteArray())
                            }
                            val endTime = System.currentTimeMillis()
                            val timeTakenSec = (endTime - startTime) / 1000.0
                            if (timeTakenSec > 0) {
                                val currentThreadHashrate = batchHashes / timeTakenSec
                                withContext(Dispatchers.Main) {
                                    // Sehr simple Hashrate-Aggregierung (nur Schätzung)
                                    hashrate = (hashrate * 0.9 + currentThreadHashrate * 0.1)
                                }
                            }
                            totalHashes += batchHashes
                            delay(10)
                        }
                    })
                }
            } else {
                isInitializing = false
            }
        }
    }

    private fun stopMining() {
        isMining = false
        isInitializing = false
        hashrate = 0.0
        miningJobs.forEach { it.cancel() }
        miningJobs.clear()
    }
}

@Composable
fun MinerScreen(
    modifier: Modifier = Modifier,
    viewModel: MinerViewModel = viewModel()
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "All Miner",
            style = MaterialTheme.typography.headlineLarge
        )
        
        Text(
            text = viewModel.engineInfo,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )

        var expanded by remember { mutableStateOf(false) }

        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "Coin: ${viewModel.selectedCoin}")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth()
            ) {
                viewModel.coins.forEach { coin ->
                    DropdownMenuItem(
                        text = { Text(coin) },
                        onClick = {
                            viewModel.selectedCoin = coin
                            expanded = false
                        }
                    )
                }
            }
        }

        OutlinedTextField(
            value = viewModel.walletAddress,
            onValueChange = { viewModel.walletAddress = it },
            label = { Text("Wallet Address") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = viewModel.poolUrl,
            onValueChange = { viewModel.poolUrl = it },
            label = { Text("UnMineable Pool URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = viewModel.startArgs,
            onValueChange = { viewModel.startArgs = it },
            label = { Text("Start Arguments (e.g., --threads 4)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(text = "CPU Threads: ${viewModel.numThreads}", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = viewModel.numThreads.toFloat(),
                onValueChange = { viewModel.numThreads = it.toInt() },
                valueRange = 1f..Runtime.getRuntime().availableProcessors().toFloat(),
                steps = Runtime.getRuntime().availableProcessors() - 2,
                enabled = !viewModel.isMining
            )
            Text(
                text = "Higher Intensity = More Heat & Battery Usage",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    viewModel.stopReason.isNotEmpty() -> MaterialTheme.colorScheme.errorContainer
                    viewModel.isInitializing -> MaterialTheme.colorScheme.tertiaryContainer
                    viewModel.isMining -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = when {
                        viewModel.stopReason.isNotEmpty() -> "STOPPED: ${viewModel.stopReason}"
                        viewModel.isInitializing -> "Initializing RandomX Dataset..."
                        viewModel.isMining -> "Mining in progress..."
                        else -> "Stopped"
                    },
                    color = if (viewModel.stopReason.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Battery: ${viewModel.batteryLevel}%", style = MaterialTheme.typography.labelSmall)
                    if (viewModel.isDeviceHot) {
                        Text(text = "Device HOT", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text(
                    text = "${String.format("%.2f", viewModel.hashrate)} H/s",
                    style = MaterialTheme.typography.displayMedium
                )
                Text(text = "Total Hashes: ${viewModel.totalHashes}")
            }
        }

        Button(
            onClick = { viewModel.toggleMining() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (viewModel.isMining) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (viewModel.isMining) "STOP" else "START")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MinerScreenPreview() {
    AllMinerTheme {
        MinerScreen()
    }
}
