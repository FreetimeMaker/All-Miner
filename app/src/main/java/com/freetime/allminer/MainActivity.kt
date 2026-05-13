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

    private external fun initRandomX(key: String): Boolean
    private external fun performRandomXHash(input: ByteArray): Long
    private external fun getEngineVersion(): String

    var isMining by mutableStateOf(false)
    var isInitializing by mutableStateOf(false)
    var hashrate by mutableDoubleStateOf(0.0)
    var walletAddress by mutableStateOf("")
    var poolUrl by mutableStateOf("pool.minexmr.com:4444")
    var totalHashes by mutableLongStateOf(0L)
    var selectedCoin by mutableStateOf("Monero (XMR)")
    var engineInfo by mutableStateOf(getEngineVersion())
    
    // Akku- und Hitzeschutz Status
    var batteryLevel by mutableIntStateOf(100)
    var isBatteryLow by mutableStateOf(false)
    var isDeviceHot by mutableStateOf(false)
    var stopReason by mutableStateOf("")

    private var miningJob: Job? = null
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
                    stopReason = if (isDeviceHot) "Gerät zu heiß!" else "Akku zu schwach (< 20%)"
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
        batteryLevel = (level * 100 / scale.toFloat()).toInt()
        
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

    val coins = listOf("Monero (XMR)", "Ethereum Classic (ETC)", "Ravencoin (RVN)", "Dogecoin (DOGE)")

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
            stopReason = if (isDeviceHot) "Gerät zu heiß!" else "Akku zu schwach"
            return
        }

        isInitializing = true
        viewModelScope.launch(Dispatchers.Default) {
            // RandomX benötigt eine Initialisierung (Key/Seed)
            val success = initRandomX("default_seed_hash")
            isInitializing = false
            
            if (success) {
                isMining = true
                miningJob = launch {
                    while (isActive) {
                        val startTime = System.currentTimeMillis()
                        
                        // Simuliere Hashing-Batch
                        var batchHashes = 0L
                        for (i in 1..10) {
                            batchHashes += performRandomXHash("input_data_to_hash_$totalHashes".toByteArray())
                        }
                        
                        val endTime = System.currentTimeMillis()
                        val timeTakenSec = (endTime - startTime) / 1000.0
                        
                        if (timeTakenSec > 0) {
                            hashrate = batchHashes / timeTakenSec
                        }
                        
                        totalHashes += batchHashes
                        delay(10)
                    }
                }
            }
        }
    }

    private fun stopMining() {
        isMining = false
        isInitializing = false
        hashrate = 0.0
        miningJob?.cancel()
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
            label = { Text("Wallet-Adresse") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = viewModel.poolUrl,
            onValueChange = { viewModel.poolUrl = it },
            label = { Text("Pool URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

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
                        viewModel.stopReason.isNotEmpty() -> "STOPPT: ${viewModel.stopReason}"
                        viewModel.isInitializing -> "Initialisiere RandomX Dataset..."
                        viewModel.isMining -> "Mining läuft..."
                        else -> "Gestoppt"
                    },
                    color = if (viewModel.stopReason.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Akku: ${viewModel.batteryLevel}%", style = MaterialTheme.typography.labelSmall)
                    if (viewModel.isDeviceHot) {
                        Text(text = "Gerät HEISS", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text(
                    text = "${String.format("%.2f", viewModel.hashrate)} H/s",
                    style = MaterialTheme.typography.displayMedium
                )
                Text(text = "Gesamt Hashes: ${viewModel.totalHashes}")
            }
        }

        Button(
            onClick = { viewModel.toggleMining() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (viewModel.isMining) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (viewModel.isMining) "STOPPEN" else "STARTEN")
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
