package jp.a09848281.bluetooth_explorer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

class MainActivity : Activity(), SensorEventListener {
    private val permissionRequest = 41
    private val csvRequest = 42
    private val handler = Handler(Looper.getMainLooper())
    private var adapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var wifiManager: WifiManager? = null
    private var locationManager: LocationManager? = null
    private var sensorManager: SensorManager? = null
    private var currentLocation: Location? = null
    private var scanning = false
    private var receiverRegistered = false
    private var selectedAddress: String? = null
    private var heading: Float? = null
    private var steps = 0
    private var gyroRate = 0.0
    private var lastGyroTime = 0L
    private val accelMagnitudes = mutableListOf<Double>()
    private var lastWifiScan = 0L

    private lateinit var status: TextView
    private lateinit var locationText: TextView
    private lateinit var sensorText: TextView
    private lateinit var bleText: TextView
    private lateinit var wifiText: TextView
    private lateinit var estimateText: TextView
    private lateinit var diagnosticsText: TextView
    private lateinit var bleButton: Button
    private lateinit var calibrateButton: Button

    private data class Sample(val rssi: Int, val time: Long)
    private data class BleDevice(val name: String, val address: String, val samples: MutableList<Sample> = mutableListOf(), var txPower: Int? = null)
    private data class WifiAp(val ssid: String, val bssid: String, val rssi: Int, val frequency: Int, val capabilities: String)
    private data class Observation(val lat: Double, val lon: Double, val rssi: Int, val accuracy: Float, val time: Long)
    private data class CalPoint(val distance: Double, val rssi: Double)
    private data class Model(val intercept: Double, val slope: Double, val r2: Double, val count: Int)

    private val ble = linkedMapOf<String, BleDevice>()
    private val wifi = linkedMapOf<String, WifiAp>()
    private val observations = linkedMapOf<String, MutableList<Observation>>()
    private val prefs by lazy { getSharedPreferences("wireless_explorer", Context.MODE_PRIVATE) }
    private val stopTask = Runnable { stopBle("120秒の探索を終了しました") }

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try {
                val address = result.device.address ?: return
                val name = result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                    ?: result.device.name?.takeIf { it.isNotBlank() } ?: "名称非公開の機器"
                val item = ble.getOrPut(address) { BleDevice(name, address) }
                item.samples.add(Sample(result.rssi, System.currentTimeMillis()))
                while (item.samples.size > 60) item.samples.removeAt(0)
                item.txPower = result.scanRecord?.txPowerLevel?.takeIf { it in -127..20 } ?: item.txPower
                runOnUiThread { renderBle() }
            } catch (_: SecurityException) {
                runOnUiThread { status.text = "Bluetooth権限がありません。" }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            runOnUiThread { status.text = "BLEスキャン失敗: " + errorCode }
        }
    }

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { readWifi() }
    }

    private val locationListener = LocationListener { loc ->
        val old = currentLocation
        if (old == null || loc.time >= old.time || (loc.accuracy < old.accuracy && System.currentTimeMillis() - old.time > 5000L)) {
            currentLocation = loc
            runOnUiThread { renderLocation() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        loadObservations()
        buildUi()
        requestPermissionsIfNeeded()
        diagnose()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 24)
            setBackgroundColor(0xFF0B1220.toInt())
        }
        root.addView(label("📡 Wireless Intelligence Explorer", 23f, true))
        root.addView(label("Android 13対応・無線環境調査ツール", 13f))
        status = label("端末と権限を確認しています。", 13f)
        root.addView(status)
        root.addView(section("📍 位置・センサー"))
        locationText = label("現在地: 取得待ち", 13f)
        sensorText = label("センサー情報を確認しています。", 13f)
        root.addView(locationText)
        root.addView(sensorText)

        root.addView(section("🔵 Bluetooth LE分析"))
        bleButton = Button(this).apply {
            text = "Bluetooth探索を開始（120秒）"
            setOnClickListener { if (scanning) stopBle("手動停止") else startBle() }
        }
        calibrateButton = Button(this).apply {
            text = "選択機器の実測校正"
            setOnClickListener { showCalibration() }
        }
        root.addView(bleButton)
        root.addView(calibrateButton)
        root.addView(label("検出結果（タップして校正対象を選択）", 12f))
        bleText = label("まだ機器を検出していません。", 13f)
        bleText.setOnClickListener { chooseBle() }
        root.addView(bleText)

        root.addView(section("📶 Wi-Fi分析"))
        root.addView(Button(this).apply {
            text = "Wi-Fiをスキャン"
            setOnClickListener { startWifi() }
        })
        wifiText = label("まだアクセスポイントを取得していません。", 13f)
        root.addView(wifiText)

        root.addView(section("🗺️ Wi-Fi観測地点・位置候補"))
        estimateText = label("Wi-Fiスキャン時にGPS精度が良ければ、観測地点を端末内に保存します。", 13f)
        root.addView(estimateText)
        root.addView(Button(this).apply {
            text = "Wi-Fi観測履歴を消去"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("観測履歴を消去")
                    .setMessage("端末内に保存したWi-Fi測定地点を消去します。")
                    .setNegativeButton("キャンセル", null)
                    .setPositiveButton("消去") { _, _ ->
                        observations.clear()
                        prefs.edit().remove("bssids").apply()
                        renderEstimates()
                        status.text = "Wi-Fi観測履歴を消去しました。"
                    }.show()
            }
        })

        root.addView(section("🧪 端末能力診断"))
        diagnosticsText = label("診断中…", 13f)
        root.addView(diagnosticsText)
        root.addView(Button(this).apply {
            text = "端末能力を再診断"
            setOnClickListener { diagnose() }
        })
        root.addView(Button(this).apply {
            text = "測定データをCSV出力"
            setOnClickListener { exportCsv() }
        })

        root.addView(section("ℹ️ 精度とプライバシー"))
        root.addView(label(
            "• RSSIだけでは正確な距離・方向は測れません。未校正の距離は表示しません。\n" +
            "• Bluetooth距離は実測校正が3距離以上あり、モデル品質が十分な場合だけ参考値を表示します。\n" +
            "• Wi-Fiの最強観測地点はルーター設置場所とは限りません。実座標を断定しません。\n" +
            "• GPS精度25m以内・位置更新30秒以内の観測だけを位置分析に採用します。\n" +
            "• 測定データは端末内に保存し、外部サービスへ送信しません。",
            12f
        ).apply { setTextColor(0xFFFFD58A.toInt()) })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun label(textValue: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = textValue
        textSize = size
        setTextColor(0xFFE6EEF9.toInt())
        setPadding(0, 8, 0, 8)
        if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
    }

    private fun section(value: String) = TextView(this).apply {
        text = value
        textSize = 18f
        setTextColor(0xFF78C7FF.toInt())
        setPadding(0, 18, 0, 4)
        setTypeface(null, android.graphics.Typeface.BOLD)
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission() = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun requestPermissionsIfNeeded() {
        val missing = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            missing.add(Manifest.permission.BLUETOOTH_SCAN)
            missing.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val ask = missing.filter { !hasPermission(it) }
        if (ask.isNotEmpty()) ActivityCompat.requestPermissions(this, ask.toTypedArray(), permissionRequest)
        else startLocation()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequest) {
            if (hasLocationPermission()) startLocation()
            status.text = "権限確認完了。許可された機能を利用できます。"
            diagnose()
        }
    }

    private fun startLocation() {
        if (!hasLocationPermission()) return
        try {
            val lm = locationManager ?: return
            val last = listOfNotNull(
                try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (_: Exception) { null },
                try { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { null }
            )
            currentLocation = last.maxByOrNull { it.time } ?: currentLocation
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
                try {
                    if (lm.isProviderEnabled(provider)) lm.requestLocationUpdates(provider, 2000L, 1f, locationListener, Looper.getMainLooper())
                } catch (_: Exception) { }
            }
            currentLocation?.let { renderLocation() }
        } catch (_: SecurityException) {
            status.text = "位置情報の権限がありません。"
        }
    }

    private fun renderLocation() {
        val loc = currentLocation
        if (loc == null) {
            locationText.text = "現在地: 取得待ち（位置情報をオンにしてください）"
            return
        }
        val age = ((System.currentTimeMillis() - loc.time).coerceAtLeast(0L) / 1000L)
        val quality = when {
            loc.accuracy <= 5f -> "非常に良い"
            loc.accuracy <= 10f -> "良い"
            loc.accuracy <= 25f -> "分析候補"
            else -> "精度不足"
        }
        locationText.text = String.format(Locale.JAPAN,
            "現在地: %.6f, %.6f\n推定精度: ±%.1fm（%s） / 更新: %d秒前",
            loc.latitude, loc.longitude, loc.accuracy, quality, age)
    }

    private fun registerSensors() {
        val sm = sensorManager ?: return
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val magnitude = sqrt(event.values[0].toDouble().pow(2) + event.values[1].toDouble().pow(2) + event.values[2].toDouble().pow(2))
                accelMagnitudes.add(magnitude)
                while (accelMagnitudes.size > 24) accelMagnitudes.removeAt(0)
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyroRate = sqrt(event.values[0].toDouble().pow(2) + event.values[1].toDouble().pow(2) + event.values[2].toDouble().pow(2))
                lastGyroTime = System.currentTimeMillis()
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                val matrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(matrix, orientation)
                heading = (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f
            }
            Sensor.TYPE_STEP_DETECTOR -> steps++
        }
        renderSensors()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun renderSensors() {
        val sm = sensorManager ?: return
        fun has(type: Int) = sm.getDefaultSensor(type) != null
        val direction = heading?.let { String.format(Locale.US, "%.0f°", it) } ?: "取得不可"
        val motion = motionState()
        sensorText.text = "端末状態: " + motion + " / 方位: " + direction + "\n" +
            "加速度: " + (if (has(Sensor.TYPE_ACCELEROMETER)) "あり" else "なし") +
            " / ジャイロ: " + (if (has(Sensor.TYPE_GYROSCOPE)) "あり" else "なし") +
            " / 回転ベクトル: " + (if (has(Sensor.TYPE_ROTATION_VECTOR)) "あり" else "なし") +
            " / アプリ起動後の歩数イベント: " + steps
    }

    private fun diagnose() {
        val pm = packageManager
        val sm = sensorManager
        val api = Build.VERSION.SDK_INT
        val rtt = api >= 28 && pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)
        val uwb = api >= 31 && pm.hasSystemFeature(PackageManager.FEATURE_UWB)
        fun sensor(type: Int) = sm?.getDefaultSensor(type) != null
        diagnosticsText.text =
            "Android " + Build.VERSION.RELEASE + " / API " + api + "\n" +
            "BLE: " + pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) +
            " / Wi-Fi: " + pm.hasSystemFeature(PackageManager.FEATURE_WIFI) +
            " / GPS: " + pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS) + "\n" +
            "Wi-Fi RTT: " + (if (rtt) "端末機能あり（AP対応も必要）" else "非対応/未確認") +
            " / UWB: " + (if (uwb) "端末機能あり" else "非対応") + "\n" +
            "Channel Sounding: Android 13では標準APIなし\n" +
            "加速度: " + sensor(Sensor.TYPE_ACCELEROMETER) +
            " / ジャイロ: " + sensor(Sensor.TYPE_GYROSCOPE) +
            " / 回転ベクトル: " + sensor(Sensor.TYPE_ROTATION_VECTOR) +
            " / 歩数検出: " + sensor(Sensor.TYPE_STEP_DETECTOR) + "\n" +
            "位置権限: " + hasLocationPermission() +
            " / Bluetoothスキャン権限: " + (api < 31 || hasPermission(Manifest.permission.BLUETOOTH_SCAN))
        renderSensors()
    }

    private fun startBle() {
        if (Build.VERSION.SDK_INT >= 31 &&
            (!hasPermission(Manifest.permission.BLUETOOTH_SCAN) || !hasPermission(Manifest.permission.BLUETOOTH_CONNECT))) {
            requestPermissionsIfNeeded()
            status.text = "Bluetoothの「付近のデバイス」権限を許可してください。"
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
        ble.clear()
        selectedAddress = null
        renderBle()
        try {
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0L).build()
            scanner?.startScan(null, settings, bleCallback)
            scanning = true
            bleButton.text = "Bluetooth探索を停止"
            status.text = "BLE探索中。120秒間、機器ごとに最大60サンプルを記録します。"
            handler.removeCallbacks(stopTask)
            handler.postDelayed(stopTask, 120000L)
        } catch (e: Exception) {
            status.text = "BLE開始エラー: " + e.message
        }
    }

    private fun stopBle(message: String) {
        if (scanning) {
            try { scanner?.stopScan(bleCallback) } catch (_: Exception) { }
            scanning = false
        }
        handler.removeCallbacks(stopTask)
        if (::bleButton.isInitialized) bleButton.text = "Bluetooth探索を開始（120秒）"
        if (::status.isInitialized) status.text = message + " / 検出: " + ble.size + "台"
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    private fun deviation(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val avg = values.average()
        return sqrt(values.sumOf { (it - avg).pow(2) } / (values.size - 1))
    }

    private fun motionState(): String {
        val sm = sensorManager ?: return "センサー情報なし"
        val hasAccel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        val hasGyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        if (hasAccel && accelMagnitudes.size < 8) return "静止判定中"
        if (hasAccel && deviation(accelMagnitudes) > 0.45) return "動作中・測定待ち"
        if (hasGyro && System.currentTimeMillis() - lastGyroTime < 3000L && gyroRate > 0.12) return "端末回転中・測定待ち"
        val loc = currentLocation
        if (!hasAccel && loc != null && loc.hasSpeed() && loc.speed > 0.5f) return "移動中・測定待ち"
        return "静止状態の可能性"
    }

    private fun isMotionStable(): Boolean {
        val state = motionState()
        return state == "静止状態の可能性"
    }

    private fun calibrationKey(address: String) = "cal_" + address.replace(":", "")

    private fun loadCalibration(address: String): List<CalPoint> {
        return (prefs.getString(calibrationKey(address), "") ?: "").split(";").mapNotNull { row ->
            val p = row.split(",")
            if (p.size != 2) null else {
                val d = p[0].toDoubleOrNull()
                val r = p[1].toDoubleOrNull()
                if (d != null && r != null && d > 0.0) CalPoint(d, r) else null
            }
        }
    }

    private fun fit(points: List<CalPoint>): Model? {
        if (points.size < 3 || points.map { it.distance }.distinct().size < 3) return null
        val x = points.map { log10(it.distance) }
        val y = points.map { it.rssi }
        val xm = x.average()
        val ym = y.average()
        val denom = x.sumOf { (it - xm).pow(2) }
        if (denom <= 1e-9) return null
        val slope = x.indices.sumOf { (x[it] - xm) * (y[it] - ym) } / denom
        if (slope >= -1.0 || slope < -100.0) return null
        val intercept = ym - slope * xm
        val total = y.sumOf { (it - ym).pow(2) }
        val residual = y.indices.sumOf { (y[it] - (intercept + slope * x[it])).pow(2) }
        val r2 = if (total <= 1e-9) 0.0 else (1.0 - residual / total).coerceIn(0.0, 1.0)
        return Model(intercept, slope, r2, points.size)
    }

    private fun calibratedDistance(address: String, rssi: Double): Pair<Double, Model>? {
        val points = loadCalibration(address)
        val model = fit(points) ?: return null
        if (model.r2 < 0.70) return null
        val distance = 10.0.pow((rssi - model.intercept) / model.slope)
        val calibratedMin = points.minOf { it.distance }
        val calibratedMax = points.maxOf { it.distance }
        // Never extrapolate beyond the distances that were actually measured.
        if (!distance.isFinite() || distance < calibratedMin || distance > calibratedMax) return null
        return Pair(distance, model)
    }

    private fun renderBle() {
        if (!::bleText.isInitialized) return
        if (ble.isEmpty()) {
            bleText.text = "検出待ちです。BLE広告を発信している機器が対象です。"
            return
        }
        bleText.text = ble.values.sortedByDescending { median(it.samples.map { s -> s.rssi.toDouble() }) }.joinToString("\n\n") { d ->
            val values = d.samples.map { it.rssi.toDouble() }
            val med = median(values)
            val sd = deviation(values)
            val stability = when {
                values.size < 5 -> "データ不足"
                sd <= 3.0 -> "安定"
                sd <= 7.0 -> "やや変動"
                else -> "不安定"
            }
            val trend = if (values.size >= 6) {
                val delta = values.takeLast(3).average() - values.take(3).average()
                when {
                    delta >= 4.0 -> "接近の可能性"
                    delta <= -4.0 -> "離れる可能性"
                    else -> "大きな変化なし"
                }
            } else "傾向判定には追加測定が必要"
            val estimate = calibratedDistance(d.address, med)
            val distance = if (estimate == null) "距離: 表示不可（3距離以上の校正とモデル品質が必要）"
                else String.format(Locale.US, "校正モデル推定: %.1fm（R²=%.2f）", estimate.first, estimate.second.r2)
            val selected = if (selectedAddress == d.address) " ★校正対象" else ""
            d.name + selected + "\nRSSI中央値: " + med.roundToInt() + " dBm / 平均: " +
                String.format(Locale.US, "%.1f", values.average()) + " dBm\nばらつき: σ=" +
                String.format(Locale.US, "%.1f", sd) + " dB（" + stability + "） / サンプル: " +
                values.size + "回\n" + trend + "\n" + distance + "\n広告TX Power: " +
                (d.txPower?.toString() ?: "不明") + " dBm（距離計算には未使用）\nID: " + d.address
        }
    }

    private fun chooseBle() {
        val devices = ble.values.toList()
        if (devices.isEmpty()) {
            status.text = "先にBluetooth探索を行ってください。"
            return
        }
        AlertDialog.Builder(this).setTitle("校正する機器を選択")
            .setItems(devices.map { it.name + " (" + it.address + ")" }.toTypedArray()) { _, index ->
                selectedAddress = devices[index].address
                calibrateButton.text = "選択機器を校正: " + devices[index].name
                renderBle()
            }.show()
    }

    private fun showCalibration() {
        val address = selectedAddress
        if (address == null || !ble.containsKey(address)) {
            chooseBle()
            return
        }
        val device = ble[address] ?: return
        val current = median(device.samples.map { it.rssi.toDouble() })
        val input = EditText(this).apply {
            hint = "実測距離（m）例: 2.0"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val points = loadCalibration(address)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
            addView(TextView(this@MainActivity).apply {
                text = "機器: " + device.name + "\n現在のRSSI中央値: " + current.roundToInt() +
                    " dBm\nこの場所から機器までの実測距離を入力してください。\n異なる距離で最低3点が必要です。\n登録済み: " + points.size + "点"
            })
            addView(input)
        }
        AlertDialog.Builder(this).setTitle("実測校正データを登録").setView(box)
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("登録") { _, _ ->
                val distance = input.text.toString().trim().replace(",", ".").toDoubleOrNull()
                if (distance == null || distance !in 0.2..100.0 || current.isNaN() || device.samples.size < 3) {
                    status.text = "登録不可。距離0.2〜100m、RSSIサンプル3回以上が必要です。"
                } else {
                    val updated = (points + CalPoint(distance, current)).takeLast(30)
                    prefs.edit().putString(calibrationKey(address),
                        updated.joinToString(";") { it.distance.toString() + "," + it.rssi.toString() }).apply()
                    val model = fit(updated)
                    status.text = if (model == null) "校正を保存しました。異なる距離で3点以上登録してください。"
                        else String.format(Locale.US, "校正モデル更新。R²=%.2f。0.70未満では距離を表示しません。", model.r2)
                    renderBle()
                }
            }.show()
    }

    private fun startWifi() {
        if (!hasLocationPermission()) {
            requestPermissionsIfNeeded()
            status.text = "Wi-Fiスキャン結果の取得には位置情報権限が必要です。"
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastWifiScan < 30000L) {
            status.text = "Androidのスキャン制限を避けるため30秒以上あけてください。"
            return
        }
        try {
            if (!receiverRegistered) {
                val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(wifiReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                else @Suppress("DEPRECATION") registerReceiver(wifiReceiver, filter)
                receiverRegistered = true
            }
            lastWifiScan = now
            val started = wifiManager?.startScan() ?: false
            if (started) status.text = "Wi-Fiスキャンを要求しました。結果を待っています。"
            else {
                status.text = "新しいスキャンを開始できません。前回の結果を読み込みます。"
                readWifi()
            }
        } catch (e: Exception) {
            status.text = "Wi-Fiスキャンエラー: " + e.message
        }
    }

    private fun readWifi() {
        try {
            wifi.clear()
            (wifiManager?.scanResults ?: emptyList()).forEach { result ->
                val bssid = result.BSSID ?: return@forEach
                val ssid = result.SSID?.takeIf { it.isNotBlank() } ?: "(SSID非公開)"
                wifi[bssid] = WifiAp(ssid, bssid, result.level, result.frequency, result.capabilities ?: "")
            }
            val loc = currentLocation
            val age = if (loc == null) Long.MAX_VALUE else System.currentTimeMillis() - loc.time
            if (loc != null && age in 0..30000L && loc.accuracy <= 25f && isMotionStable()) {
                wifi.values.forEach { ap ->
                    val list = observations.getOrPut(ap.bssid) { mutableListOf() }
                    list.add(Observation(loc.latitude, loc.longitude, ap.rssi, loc.accuracy, System.currentTimeMillis()))
                    while (list.size > 300) list.removeAt(0)
                    saveObservations(ap.bssid, list)
                }
                status.text = "Wi-Fi " + wifi.size + "件。GPS精度±" + loc.accuracy.roundToInt() + "mの観測を保存しました。"
            } else status.text = "Wi-Fi " + wifi.size + "件。GPS精度不足・位置情報が古い・端末動作中のため地点保存なし。"
            renderWifi()
            renderEstimates()
        } catch (e: SecurityException) {
            status.text = "Wi-Fi結果の取得権限がありません。"
        }
    }

    private fun channel(frequency: Int): String = when {
        frequency in 2412..2472 -> "ch " + ((frequency - 2407) / 5)
        frequency == 2484 -> "ch 14"
        frequency in 5000..5895 -> "ch " + ((frequency - 5000) / 5)
        frequency in 5955..7115 -> "6GHz帯"
        else -> "不明"
    }

    private fun renderWifi() {
        if (wifi.isEmpty()) {
            wifiText.text = "アクセスポイントが見つかりません。Wi-Fiをオンにして再試行してください。"
            return
        }
        wifiText.text = wifi.values.sortedByDescending { it.rssi }.joinToString("\n\n") { ap ->
            val level = when {
                ap.rssi >= -50 -> "非常に強い"
                ap.rssi >= -60 -> "強い"
                ap.rssi >= -70 -> "普通"
                ap.rssi >= -80 -> "弱い"
                else -> "非常に弱い"
            }
            "📶 " + ap.ssid + "\n強度: " + ap.rssi + " dBm（" + level + "）\n周波数: " +
                ap.frequency + " MHz / " + channel(ap.frequency) + "\n" + ap.capabilities +
                "\nBSSID: " + ap.bssid + "\n観測記録: " + (observations[ap.bssid]?.size ?: 0) + "件"
        }
    }

    private fun distance(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val radius = 6371000.0
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val v = sin(dLat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).pow(2)
        return 2 * radius * asin(sqrt(v.coerceIn(0.0, 1.0)))
    }

    private fun distinctPoints(records: List<Observation>): List<Observation> {
        val out = mutableListOf<Observation>()
        records.sortedByDescending { it.rssi }.forEach { candidate ->
            val index = out.indexOfFirst { distance(it.lat, it.lon, candidate.lat, candidate.lon) < 8.0 }
            if (index < 0) out.add(candidate)
            else if (candidate.accuracy < out[index].accuracy) out[index] = candidate
        }
        return out
    }

    private fun renderEstimates() {
        val lines = observations.entries.mapNotNull { entry ->
            val points = distinctPoints(entry.value)
            if (points.isEmpty()) return@mapNotNull null
            val strongest = points.maxByOrNull { it.rssi } ?: return@mapNotNull null
            val avg = points.map { it.rssi.toDouble() }.average()
            val top = points.sortedByDescending { it.rssi }.take(5)
            val spread = top.maxOf { distance(strongest.lat, strongest.lon, it.lat, it.lon) }
            val name = wifi[entry.key]?.ssid ?: "保存済みアクセスポイント"
            val quality = when {
                points.size >= 5 && points.all { it.accuracy <= 10f } -> "複数地点の電波傾向あり"
                points.size >= 3 -> "参考情報（GPS誤差・屋内反射に注意）"
                else -> "測定地点不足"
            }
            "📍 " + name + "\n異なる観測地点: " + points.size + " / 平均RSSI: " +
                String.format(Locale.US, "%.1f", avg) + " dBm\n最強観測地点: " +
                String.format(Locale.US, "%.6f, %.6f", strongest.lat, strongest.lon) +
                "\n上位地点の広がり: 約" + spread.roundToInt() + "m / " + quality +
                "\n※最強地点はルーターの設置場所とは限りません。"
        }
        estimateText.text = if (lines.isEmpty())
            "まだ観測地点がありません。Wi-Fiスキャン時にGPS精度25m以内・位置更新30秒以内なら自動保存します。\n同じBSSIDを8m以上離れた3地点以上で測定してください。"
        else lines.joinToString("\n\n")
    }

    private fun loadObservations() {
        val ids = (prefs.getString("bssids", "") ?: "").split(";").filter { it.isNotBlank() }
        ids.forEach { id ->
            val raw = prefs.getString("obs_" + id, "") ?: return@forEach
            val list = raw.split(";").mapNotNull { row ->
                val p = row.split(",")
                if (p.size != 5) null else {
                    val lat = p[0].toDoubleOrNull()
                    val lon = p[1].toDoubleOrNull()
                    val rssi = p[2].toIntOrNull()
                    val acc = p[3].toFloatOrNull()
                    val time = p[4].toLongOrNull()
                    if (lat != null && lon != null && rssi != null && acc != null && time != null)
                        Observation(lat, lon, rssi, acc, time) else null
                }
            }
            if (list.isNotEmpty()) observations[id] = list.toMutableList()
        }
    }

    private fun saveObservations(id: String, list: List<Observation>) {
        val ids = (prefs.getString("bssids", "") ?: "").split(";").filter { it.isNotBlank() }.toMutableSet()
        ids.add(id)
        val raw = list.takeLast(300).joinToString(";") {
            listOf(it.lat, it.lon, it.rssi, it.accuracy, it.time).joinToString(",")
        }
        prefs.edit().putString("bssids", ids.joinToString(";")).putString("obs_" + id, raw).apply()
    }

    private fun exportCsv() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "wireless_measurements_" + stamp + ".csv")
        }
        startActivityForResult(intent, csvRequest)
    }

    @Deprecated("Deprecated API retained for Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == csvRequest && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.openOutputStream(uri)?.use { stream ->
                    OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                        writer.write("type,name,id,rssi_dbm,latitude,longitude,accuracy_m,time_ms\n")
                        ble.values.forEach { device ->
                            device.samples.forEach { sample ->
                                writer.write("Bluetooth," + csv(device.name) + "," + csv(device.address) + "," +
                                    sample.rssi + ",,,," + sample.time + "\n")
                            }
                        }
                        observations.forEach { entry ->
                            entry.value.forEach { obs ->
                                writer.write("WiFi," + csv(wifi[entry.key]?.ssid ?: "saved AP") + "," +
                                    csv(entry.key) + "," + obs.rssi + "," + obs.lat + "," + obs.lon + "," +
                                    obs.accuracy + "," + obs.time + "\n")
                            }
                        }
                    }
                }
                status.text = "CSVを保存しました。"
            } catch (e: Exception) {
                status.text = "CSV保存エラー: " + e.message
            }
        }
    }

    private fun csv(value: String) = "\"" + value.replace("\"", "\"\"") + "\""

    override fun onResume() {
        super.onResume()
        registerSensors()
    }

    override fun onPause() {
        sensorManager?.unregisterListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        stopBle("アプリ終了")
        try { if (receiverRegistered) unregisterReceiver(wifiReceiver) } catch (_: Exception) { }
        try { locationManager?.removeUpdates(locationListener) } catch (_: Exception) { }
        sensorManager?.unregisterListener(this)
        super.onDestroy()
    }
}
