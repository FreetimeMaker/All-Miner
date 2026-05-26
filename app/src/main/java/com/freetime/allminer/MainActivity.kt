package com.freetime.allminer

import android.Manifest
import android.app.Application
import android.content.*
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.core.content.ContextCompat
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.freetime.allminer.ui.theme.AllMinerTheme

private val Context.dataStore by preferencesDataStore(name = "settings")

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

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
    private var miningService: MiningService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as MiningService.LocalBinder
            miningService = binder.getService()
            isBound = true
            // Sync status from service if it's already running
            miningService?.let {
                isMining = it.isMining
                totalHashes = it.totalHashes
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            miningService = null
        }
    }

    init {
        System.loadLibrary("allminer")
        loadSettings()
        val intent = Intent(application, MiningService::class.java)
        application.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        
        // Update loop to sync from service
        viewModelScope.launch {
            while (true) {
                miningService?.let {
                    isMining = it.isMining
                    hashrate = it.hashrate
                    totalHashes = it.totalHashes
                }
                delay(1000)
            }
        }
    }

    private external fun initRandomX(key: String, args: String): Boolean
    private external fun prepareThreads(count: Int)
    private external fun getEngineVersion(): String

    private val WALLET_KEY = stringPreferencesKey("wallet_address")
    private val POOL_KEY = stringPreferencesKey("pool_url")
    private val ARGS_KEY = stringPreferencesKey("start_args")
    private val COIN_KEY = stringPreferencesKey("selected_coin")

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
    
    var batteryLevel by mutableIntStateOf(100)
    var isBatteryLow by mutableStateOf(false)
    var isDeviceHot by mutableStateOf(false)
    var stopReason by mutableStateOf("")

    private var monitoringJob: Job? = null

    val coins = listOf(
        "DOGE", "SHIB", "PEPE", "SOL", "ADA", "XRP", "LTC", "BONK", "FLOKI",
        "MATIC", "DOT", "TRX", "LINK", "AVAX", "ETC", "RVN", "XMR", "KAS", "ALGO",
        "BABYDOGE", "SAFEMOON", "VET", "ZIL", "EOS", "DASH", "ATOM", "FTM", "NEAR"
    ).sorted()

    init {
        startMonitoring()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            try {
                val preferences = context.dataStore.data.first()
                walletAddress = preferences[WALLET_KEY] ?: ""
                poolUrl = preferences[POOL_KEY] ?: "rx.unmineable.com:3333"
                startArgs = preferences[ARGS_KEY] ?: "--cpu-max-threads-hint 50"
                selectedCoin = preferences[COIN_KEY] ?: "DOGE"
            } catch (e: Exception) {}
        }
    }

    private fun saveSettings() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            context.dataStore.edit { preferences ->
                preferences[WALLET_KEY] = walletAddress
                preferences[POOL_KEY] = poolUrl
                preferences[ARGS_KEY] = startArgs
                preferences[COIN_KEY] = selectedCoin
            }
        }
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
                delay(5000)
            }
        }
    }

    private fun updateBatteryStatus() {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = getApplication<Application>().registerReceiver(null, intentFilter)
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        batteryLevel = if (scale > 0) (level * 100 / scale.toFloat()).toInt() else 0
        isBatteryLow = batteryLevel < 20
    }

    private fun updateThermalStatus() {
        val powerManager = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as PowerManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val status = powerManager.currentThermalStatus
            isDeviceHot = status >= PowerManager.THERMAL_STATUS_MODERATE
        }
    }

    fun toggleMining() {
        if (isMining) {
            stopMining()
        } else {
            saveSettings()
            stopReason = ""
            startMining()
        }
    }

    private fun startMining() {
        if (isBatteryLow || isDeviceHot) {
            stopReason = if (isDeviceHot) "Device too hot!" else "Battery too low"
            return
        }

        isInitializing = true
        viewModelScope.launch(Dispatchers.Default) {
            val success = initRandomX("unmineable_rx_seed", startArgs)
            if (success) {
                prepareThreads(numThreads)
                withContext(Dispatchers.Main) {
                    isInitializing = false
                    isMining = true
                    
                    val intent = Intent(getApplication(), MiningService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        getApplication<Application>().startForegroundService(intent)
                    } else {
                        getApplication<Application>().startService(intent)
                    }

                    miningService?.startMining(selectedCoin, walletAddress, numThreads, startArgs)
                }
            } else {
                withContext(Dispatchers.Main) { isInitializing = false }
            }
        }
    }

    private fun stopMining() {
        isMining = false
        isInitializing = false
        hashrate = 0.0
        miningService?.stopMining()
    }

    override fun onCleared() {
        super.onCleared()
        if (isBound) {
            getApplication<Application>().unbindService(connection)
        }
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
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
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
            label = { Text("Pool URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = viewModel.startArgs,
            onValueChange = { viewModel.startArgs = it },
            label = { Text("Start Arguments") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(text = "CPU Threads: ${viewModel.numThreads}", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = viewModel.numThreads.toFloat(),
                onValueChange = { viewModel.numThreads = it.toInt() },
                valueRange = 1f..Runtime.getRuntime().availableProcessors().toFloat(),
                steps = if (Runtime.getRuntime().availableProcessors() > 1) Runtime.getRuntime().availableProcessors() - 1 else 0,
                enabled = !viewModel.isMining
            )
            Text(
                text = "Higher Intensity = More Heat & Battery Usage",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
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
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = when {
                        viewModel.stopReason.isNotEmpty() -> "STOPPED: ${viewModel.stopReason}"
                        viewModel.isInitializing -> "Initializing RandomX..."
                        viewModel.isMining -> "Mining Active"
                        else -> "System Ready"
                    },
                    style = MaterialTheme.typography.titleLarge
                )
                
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(text = "Battery", style = MaterialTheme.typography.labelSmall)
                        Text(text = "${viewModel.batteryLevel}%", style = MaterialTheme.typography.bodyLarge)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(text = "Status", style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = if (viewModel.isDeviceHot) "OVERHEATING" else "COOL",
                            color = if (viewModel.isDeviceHot) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "Current Hashrate", style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = "${String.format("%.2f", viewModel.hashrate)} H/s",
                        style = MaterialTheme.typography.displayMedium
                    )
                }

                LinearProgressIndicator(
                    progress = { if (viewModel.isMining) 1f else 0f },
                    modifier = Modifier.fillMaxWidth().height(8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Total Hashes:", style = MaterialTheme.typography.bodyMedium)
                    Text(text = "${viewModel.totalHashes}", style = MaterialTheme.typography.bodyMedium)
                }
                
                if (viewModel.isMining) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(text = "Mining Config:", style = MaterialTheme.typography.labelSmall)
                            Text(
                                text = "Address: ${viewModel.selectedCoin}:${viewModel.walletAddress}",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        Button(
            onClick = { viewModel.toggleMining() },
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (viewModel.isMining) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                if (viewModel.isMining) "STOP MINING" else "START MINING",
                style = MaterialTheme.typography.headlineSmall
            )
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
