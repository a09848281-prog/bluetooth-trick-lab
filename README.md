# Bluetooth Explorer (Android APK)

GitHub ActionsだけでAndroid APKをビルドするBLE探索アプリです。

## 現在の機能
- AndroidのBLEスキャンで周囲の広告発信機器を探索
- 機器名、アドレス、RSSI（dBm）を表示
- RSSIから距離の参考値を計算
- 60秒で自動停止
- 同じアドレスの検出をまとめて最新RSSIに更新

## APKの作り方
1. GitHubのActionsタブを開く
2. Build Android APKを選ぶ
3. Run workflowを押す（mainブランチ）
4. 実行完了後、ワークフローの実行ページを開く
5. Artifactsのbluetooth-explorer-debug-apkをダウンロード
6. ZIPを展開し、app-debug.apkをAndroidに転送してインストール

mainブランチに対象ファイルを更新しても自動ビルドします。

## Androidでの利用
- Bluetoothをオンにする
- アプリを開いてBluetoothスキャン権限を許可
- 「周囲のBLE機器を探索」を押す

## 大切な制限
- BLE広告を発信している機器が対象です。Bluetooth機器すべてが見つかるわけではありません。イヤホン等は検出可能な広告を出していない場合があります。
- RSSI距離は目安です。送信出力の既知なビーコン等で校正しない限り、実距離とは大きく異なる場合があります。
- RSSIから方向や地図上の正確な位置は分かりません。
- 初期版は画面表示中の前景スキャンのみです。バックグラウンド常時スキャンは行いません。
- Debug APKはテスト用です。一般配布向けには署名鍵を使ったReleaseビルドが別途必要です。
