package jp.a09848281.bluetooth_explorer

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.pow

class MainActivity : Activity() {
    private val permissionRequest = 41
    private lateinit var adapter: BluetoothAdapter
    private var scanner: BluetoothLeScanner? = null
    private val handler = Handler(Looper.getMainLooper())
    private val devices = linkedMapOf<String, SeenDevice>()
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var scanButton: Button
    private var scanning = false

    data class SeenDevice(val name: String, val address: String, var rssi: Int, var txPower: Int)

    private val stopTask = Runnable { stopScan("60秒経過のため探索を終了しました") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        adapter = manager.adapter
        buildUi()
        if (adapter == null) status.text = "この端末はBluetoothに対応していません。"
        else requestPermissionsIfNeeded()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 24, 20, 20)
            setBackgroundColor(0xFF0B1220.toInt())
        }
        val title = TextView(this).apply {
            text = "📡 Bluetooth Explorer"
            textSize = 25f
            setTextColor(0xFFFFFFFF.toInt())
        }
        status = TextView(this).apply {
            text = "BLE機器を探索する準備ができています。"
            textSize = 14f
            setTextColor(0xFFB8C7DC.toInt())
            setPadding(0, 14, 0, 14)
        }
        scanButton = Button(this).apply {
            text = "周囲のBLE機器を探索"
            setOnClickListener { if (scanning) stopScan("探索を停止しました") else startScan() }
        }
        val note = TextView(this).apply {
            text = "検出対象：BLE広告を発信している機器\n距離はRSSIから計算した参考値です。壁・人体・機器の向きで大きく変わります。正確な位置や方向は取得できません。"
            textSize = 12f
            setTextColor(0xFFFFD58A.toInt())
            setPadding(0, 8, 0, 14)
        }
        val heading = TextView(this).apply {
            text = "検出した機器"
            textSize = 19f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 8, 0, 8)
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(title)
        root.addView(status)
        root.addView(scanButton)
        root.addView(note)
        root.addView(heading)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun requestPermissionsIfNeeded() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), permissionRequest)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequest) {
            status.text = if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
                "権限が許可されました。探索を開始できます。"
            else "Bluetooth探索には必要な権限が必要です。設定から許可してください。"
        }
    }

    private fun startScan() {
        if (requiredPermissions().any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissionsIfNeeded()
            status.text = "先にBluetooth探索の権限を許可してください。"
            return
        }
        if (!adapter.isEnabled) {
            status.text = "Androidの設定でBluetoothをオンにしてください。"
            return
        }
        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            status.text = "BLEスキャナーを利用できません。"
            return
        }
        devices.clear()
        list.removeAllViews()
        try {
            scanner?.startScan(scanCallback)
            scanning = true
            scanButton.text = "探索を停止"
            status.text = "探索中… 60秒間スキャンします。"
            handler.postDelayed(stopTask, 60_000)
        } catch (e: SecurityException) {
            status.text = "権限エラー: ${e.message}"
        } catch (e: Exception) {
            status.text = "スキャン開始エラー: ${e.message}"
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = try { device.address ?: "不明" } catch (_: SecurityException) { "権限なし" }
            val name = result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                ?: try { device.name?.takeIf { it.isNotBlank() } } catch (_: SecurityException) { null }
                ?: "名称を公開していない機器"
            val tx = result.scanRecord?.txPowerLevel?.takeIf { it != Int.MIN_VALUE } ?: -59
            devices[address] = SeenDevice(name, address, result.rssi, tx)
            runOnUiThread { renderDevices() }
        }
        override fun onScanFailed(errorCode: Int) {
            runOnUiThread { status.text = "スキャン失敗: code=$errorCode" }
        }
    }

    private fun estimatedMeters(rssi: Int, txPower: Int): Double {
        val pathLoss = 2.2
        return 10.0.pow((txPower - rssi) / (10.0 * pathLoss)).coerceIn(0.1, 1000.0)
    }

    private fun renderDevices() {
        list.removeAllViews()
        val sorted = devices.values.sortedByDescending { it.rssi }
        if (sorted.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "まだ機器が見つかりません。BLE対応機器が広告を発信しているか確認してください。"
                setTextColor(0xFFB8C7DC.toInt())
            })
            return
        }
        sorted.forEach { d ->
            val meters = estimatedMeters(d.rssi, d.txPower)
            val distanceText = if (meters < 10) String.format(Locale.JAPAN, "%.1f m", meters) else String.format(Locale.JAPAN, "約 %.0f m", meters)
            val row = TextView(this).apply {
                text = "🔵 ${d.name}\nRSSI: ${d.rssi} dBm   推定距離: $distanceText\nID: ${d.address}\n※電波強度からの概算・方向は不明"
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(12, 12, 12, 12)
                setBackgroundColor(0xFF17263B.toInt())
            }
            val params = LinearLayout.LayoutParams(-1, -2)
            params.setMargins(0, 0, 0, 10)
            list.addView(row, params)
        }
        status.text = "探索中：${devices.size}台のBLE機器を検出（重複をまとめています）"
    }

    private fun stopScan(message: String) {
        if (scanning) {
            try { scanner?.stopScan(scanCallback) } catch (_: SecurityException) {}
            scanning = false
            scanButton.text = "周囲のBLE機器を探索"
        }
        handler.removeCallbacks(stopTask)
        status.text = "$message · 検出数: ${devices.size}台"
    }

    override fun onDestroy() {
        stopScan("アプリを終了しました")
        super.onDestroy()
    }
}
