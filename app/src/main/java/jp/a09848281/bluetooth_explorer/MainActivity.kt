package jp.a09848281.bluetooth_explorer

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.*

class MainActivity : Activity() {
    private val permissionRequest = 41
    private val handler = Handler(Looper.getMainLooper())
    private var adapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var wifiManager: WifiManager? = null
    private var locationManager: LocationManager? = null
    private var currentLocation: Location? = null
    private var scanningBle = false
    private var receiverRegistered = false
    private lateinit var status: TextView
    private lateinit var locationText: TextView
    private lateinit var bleText: TextView
    private lateinit var wifiText: TextView
    private lateinit var estimateText: TextView
    private lateinit var bleButton: Button
    private lateinit var wifiButton: Button
    private lateinit var root: LinearLayout

    private data class BleDevice(
        val name: String,
        val address: String,
        val samples: MutableList<Int> = mutableListOf(),
        var txPower: Int? = null,
        var lastSeen: Long = System.currentTimeMillis()
    )
    private data class WifiAp(
        val ssid: String,
        val bssid: String,
        val rssi: Int,
        val frequency: Int,
        val capabilities: String
    )
    private data class Observation(val lat: Double, val lon: Double, val rssi: Int, val accuracy: Float, val time: Long)

    private val bleDevices = linkedMapOf<String, BleDevice>()
    private val wifiNow = linkedMapOf<String, WifiAp>()
    private val observations = linkedMapOf<String, MutableList<Observation>>()
    private var lastWifiScanTime = 0L

    private val stopBleTask = Runnable { stopBleScan("BLE探索を終了しました") }
    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = try { result.device.address ?: return } catch (_: SecurityException) { return }
            val name = result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                ?: try { result.device.name?.takeIf { it.isNotBlank() } } catch (_: SecurityException) { null }
                ?: "名称非公開の機器"
            val item = bleDevices.getOrPut(address) { BleDevice(name, address) }
            item.samples.add(result.rssi)
            if (item.samples.size > 30) item.samples.removeAt(0)
            item.txPower = result.scanRecord?.txPowerLevel?.takeIf { it in -127..20 } ?: item.txPower
            item.lastSeen = System.currentTimeMillis()
            runOnUiThread { renderBle() }
        }
        override fun onScanFailed(errorCode: Int) {
            runOnUiThread { status.text = "Bluetoothスキャン失敗: $errorCode" }
        }
    }

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            readWifiResults()
        }
    }

    private val locationListener = LocationListener { location ->
        currentLocation = location
        runOnUiThread {
            locationText.text = String.format(
                Locale.JAPAN,
                "現在地: %.6f, %.6f\nGPS精度: 約 %.0fm",
                location.latitude, location.longitude, location.accuracy
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        buildUi()
        requestNeededPermissions()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 22, 18, 22)
            setBackgroundColor(0xFF0B1220.toInt())
        }
        val title = TextView(this).apply {
            text = "📡 Bluetooth & Wi-Fi Explorer"
            textSize = 23f
            setTextColor(0xFFFFFFFF.toInt())
        }
        status = label("権限を確認しています…", 14f)
        locationText = label("現在地: 未取得", 13f)
        bleButton = Button(this).apply {
            text = "Bluetooth探索を開始（120秒）"
            setOnClickListener { if (scanningBle) stopBleScan("停止しました") else startBleScan() }
        }
        bleText = label("Bluetooth機器はまだ検出されていません。", 13f)
        wifiButton = Button(this).apply {
            text = "Wi-Fiをスキャン"
            setOnClickListener { startWifiScan() }
        }
        wifiText = label("Wi-Fiアクセスポイントはまだ取得されていません。", 13f)
        estimateText = label("📍 ルーター位置の推定: まだ測定データがありません。\n同じWi-Fiを複数の場所で測定すると、電波が強い地点を比較できます。", 13f)
        val note = label(
            "精度について\n• RSSIは受信電波強度(dBm)です。壁・人・端末の向きで変化します。\n• BluetoothのRSSIだけでは正確な距離や方角は分かりません。未校正の距離(m)は表示しません。\n• Wi-Fiルーターの位置は複数地点のGPS測定から推定します。推定エリアであり、住所や正確な座標を保証しません。\n• Wi-FiスキャンはAndroid側で回数制限されることがあります。",
            12f
        ).apply { setTextColor(0xFFFFD58A.toInt()) }
        root.addView(title)
        root.addView(status)
        root.addView(locationText)
        root.addView(bleButton)
        root.addView(label("Bluetooth LE検出結果", 18f))
        root.addView(bleText)
        root.addView(wifiButton)
        root.addView(label("Wi-Fiアクセスポイント", 18f))
        root.addView(wifiText)
        root.addView(label("ルーター位置の分析", 18f))
        root.addView(estimateText)
        root.addView(note)
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(0xFFE6EEF9.toInt())
        setPadding(0, 9, 0, 9)
    }

    private fun requiredPermissions(): Array<String> {
        val p = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            p.add(Manifest.permission.BLUETOOTH_SCAN)
            p.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return p.toTypedArray()
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestNeededPermissions() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), permissionRequest)
        else startLocationUpdates()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequest) {
            if (hasLocationPermission()) startLocationUpdates()
            status.text = if (grantResults.isNotEmpty() && grantResults.any { it == PackageManager.PERMISSION_GRANTED })
                "権限を確認しました。必要な機能を利用できます。" else
                "権限が不足しています。Bluetooth・Wi-Fi・GPSの一部機能が使えません。"
        }
    }

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        try {
            val lm = locationManager ?: return
            val lastGps = try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (_: Exception) { null }
            val lastNet = try { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { null }
            currentLocation = listOfNotNull(lastGps, lastNet).maxByOrNull { it.time } ?: currentLocation
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            providers.forEach { provider ->
                try {
                    if (lm.isProviderEnabled(provider)) lm.requestLocationUpdates(provider, 3000L, 2f, locationListener, Looper.getMainLooper())
                } catch (_: Exception) {}
            }
            currentLocation?.let { locationListener.onLocationChanged(it) }
        } catch (e: SecurityException) {
            status.text = "位置情報の権限がありません。"
        }
    }

    private fun startBleScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
             ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) {
            requestNeededPermissions()
            status.text = "Bluetooth権限を許可してください。"
            return
        }
        val a = adapter
        if (a == null || !a.isEnabled) {
            status.text = "Bluetoothをオンにしてください。"
            return
        }
        scanner = a.bluetoothLeScanner
        if (scanner == null) {
            status.text = "BLEスキャナーを利用できません。"
            return
        }
        bleDevices.clear()
        renderBle()
        try {
            scanner?.startScan(bleCallback)
            scanningBle = true
            bleButton.text = "Bluetooth探索を停止"
            status.text = "BLE探索中です。120秒間、複数回のRSSIを記録します。"
            handler.removeCallbacks(stopBleTask)
            handler.postDelayed(stopBleTask, 120_000)
        } catch (e: Exception) {
            status.text = "BLE開始エラー: ${e.message}"
        }
    }

    private fun stopBleScan(message: String) {
        if (scanningBle) {
            try { scanner?.stopScan(bleCallback) } catch (_: Exception) {}
            scanningBle = false
        }
        handler.removeCallbacks(stopBleTask)
        if (::bleButton.isInitialized) bleButton.text = "Bluetooth探索を開始（120秒）"
        if (::status.isInitialized) status.text = "$message / 検出: ${bleDevices.size}台"
    }

    private fun median(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val s = values.sorted()
        return s[s.size / 2]
    }

    private fun renderBle() {
        if (!::bleText.isInitialized) return
        if (bleDevices.isEmpty()) {
            bleText.text = "検出待ちです。BLE広告を発信している機器が対象です。"
            return
        }
        bleText.text = bleDevices.values.sortedByDescending { median(it.samples) }.joinToString("\n\n") { d ->
            val med = median(d.samples)
            val avg = d.samples.average()
            val stability = if (d.samples.size >= 3) "中央値 $med dBm / 平均 ${"%.1f".format(Locale.US, avg)} dBm" else "測定回数が少ないため参考値"
            val tx = d.txPower?.let { "広告TX Power: $it dBm（距離計算には未使用）" } ?: "広告TX Power: 不明"
            "🔵 ${d.name}\nRSSI: $med dBm（$stability）\n受信回数: ${d.samples.size}回\n$tx\nID: ${d.address}"
        }
    }

    private fun startWifiScan() {
        if (!hasLocationPermission()) {
            requestNeededPermissions()
            status.text = "Wi-Fiスキャン結果の取得には位置情報権限が必要です。"
            return
        }
        val wm = wifiManager ?: return
        val now = System.currentTimeMillis()
        if (now - lastWifiScanTime < 30_000L) {
            status.text = "Androidのスキャン制限を避けるため、30秒以上あけてください。"
            return
        }
        try {
            if (!receiverRegistered) {
                val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(wifiReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                else @Suppress("DEPRECATION") registerReceiver(wifiReceiver, filter)
                receiverRegistered = true
            }
            lastWifiScanTime = now
            val started = wm.startScan()
            if (started) status.text = "Wi-Fiスキャンを要求しました。結果を待っています…"
            else {
                status.text = "新しいスキャンを開始できませんでした。Androidの制限中の可能性があります。前回結果を読み込みます。"
                readWifiResults()
            }
        } catch (e: SecurityException) {
            status.text = "Wi-Fiスキャン権限エラー: ${e.message}"
        } catch (e: Exception) {
            status.text = "Wi-Fiスキャンエラー: ${e.message}"
        }
    }

    private fun readWifiResults() {
        try {
            val results = wifiManager?.scanResults ?: emptyList()
            wifiNow.clear()
            results.forEach { r ->
                val bssid = r.BSSID ?: return@forEach
                val ssid = r.SSID?.takeIf { it.isNotBlank() } ?: "(SSID非公開)"
                wifiNow[bssid] = WifiAp(ssid, bssid, r.level, r.frequency, r.capabilities ?: "")
            }
            val loc = currentLocation
            val locationFresh = loc != null && System.currentTimeMillis() - loc.time <= 60_000L
            if (loc != null && locationFresh && loc.accuracy <= 50f) {
                wifiNow.values.forEach { ap ->
                    val list = observations.getOrPut(ap.bssid) { mutableListOf() }
                    list.add(Observation(loc.latitude, loc.longitude, ap.rssi, loc.accuracy, System.currentTimeMillis()))
                    if (list.size > 100) list.removeAt(0)
                }
            }
            runOnUiThread {
                renderWifi()
                status.text = "Wi-Fi結果: ${wifiNow.size}件。GPS位置が取得できていれば観測地点も記録しました。"
            }
        } catch (e: SecurityException) {
            status.text = "Wi-Fi結果を読む権限がありません。位置情報を許可してください。"
        }
    }

    private fun renderWifi() {
        if (!::wifiText.isInitialized) return
        if (wifiNow.isEmpty()) {
            wifiText.text = "アクセスポイントが見つかりません。Wi-Fiをオンにして再試行してください。"
            return
        }
        wifiText.text = wifiNow.values.sortedByDescending { it.rssi }.joinToString("\n\n") { ap ->
            val signal = when {
                ap.rssi >= -50 -> "非常に強い"
                ap.rssi >= -60 -> "強い"
                ap.rssi >= -70 -> "普通"
                ap.rssi >= -80 -> "弱い"
                else -> "非常に弱い"
            }
            "📶 ${ap.ssid}\n強度: ${ap.rssi} dBm（$signal）\n周波数: ${ap.frequency} MHz\nBSSID: ${ap.bssid}\n位置観測: ${observations[ap.bssid]?.size ?: 0}地点"
        }
        renderEstimates()
    }

    private fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val x = sin(dLat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(x))
    }

    private fun renderEstimates() {
        if (!::estimateText.isInitialized) return
        val lines = observations.entries.mapNotNull { (bssid, obs) ->
            val distinct = mutableListOf<Observation>()
            obs.sortedByDescending { it.rssi }.forEach { o ->
                if (distinct.none { distanceMeters(it.lat, it.lon, o.lat, o.lon) < 8.0 }) distinct.add(o)
            }
            if (distinct.size < 3) return@mapNotNull null
            val strongest = distinct.take(5)
            val maxRssi = strongest.maxOf { it.rssi }
            val weights = strongest.map { 10.0.pow((it.rssi - maxRssi) / 15.0) / max(10.0, it.accuracy.toDouble()) }
            val sum = weights.sum()
            val lat = strongest.indices.sumOf { strongest[it].lat * weights[it] } / sum
            val lon = strongest.indices.sumOf { strongest[it].lon * weights[it] } / sum
            val spread = strongest.maxOf { distanceMeters(lat, lon, it.lat, it.lon) }
            val name = wifiNow[bssid]?.ssid ?: "記録済みAP"
            val confidence = when {
                strongest.size >= 5 && spread < 40 -> "低〜中（候補エリア）"
                strongest.size >= 3 -> "低（参考候補）"
                else -> "不足"
            }
            "• $name\n候補中心: ${"%.5f".format(Locale.US, lat)}, ${"%.5f".format(Locale.US, lon)}\n測定地点: ${strongest.size} / 広がり: 約 ${spread.roundToInt()}m\n信頼度: $confidence"
        }
        estimateText.text = if (lines.isEmpty()) {
            "まだ位置候補を計算できません。GPS精度50m以内で、位置情報が新しい状態のときに、同じBSSIDを8m以上離れた3地点以上からスキャンしてください。"
        } else {
            "複数地点のRSSIを重み付けした候補です。ルーターの実座標ではありません。\n\n" + lines.joinToString("\n\n")
        }
    }

    override fun onDestroy() {
        stopBleScan("アプリ終了")
        try { if (receiverRegistered) unregisterReceiver(wifiReceiver) } catch (_: Exception) {}
        try { locationManager?.removeUpdates(locationListener) } catch (_: Exception) {}
        super.onDestroy()
    }
}
