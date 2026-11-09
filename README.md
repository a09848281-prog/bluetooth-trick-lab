# Bluetooth Trick Lab

Bluetooth機器をブラウザーから探索し、公開されているGATTサービス・特性の情報を確認するWebアプリです。

## 現在の機能

- Bluetooth機器の選択・接続・切断
- 公開サービスとCharacteristicの一覧表示
- Characteristicの値の読み取り
- Notify / Indicate通知の受信
- 書き込み可能なCharacteristicへの16進数データ送信（確認ダイアログ付き）
- 操作ログの表示・テキスト保存
- 実機接続なしで画面を確認するデモモード

## 起動方法

GitHub Pagesを有効にして公開するか、HTTPS環境で `index.html` を開いてください。Web Bluetooth APIは安全なコンテキストが必要です。

## 対応環境について

Web Bluetoothの対応状況はブラウザー・OSによって異なります。一般にChromium系ブラウザー（Chrome / Edge）のデスクトップ環境が適しています。iPad / iPhoneのSafariではWeb Bluetooth APIが利用できないため、実機Bluetooth接続はできません。iPadではデモ表示や画面確認を利用できます。

## 使い方

1. 「Bluetooth機器を探す」を押します。
2. ブラウザーの選択画面で、自分が使用・管理する機器を選択します。
3. 公開されたサービスを選び、Characteristicの対応機能を確認します。
4. 読み取り・通知受信を試します。
5. 書き込みは、機器の説明書で意味を確認した値だけを送信してください。

追加サービスUUID欄には、機器の仕様書などで確認したUUIDを入力できます。標準サービスと追加UUID以外は、ブラウザーの権限仕様により一覧に出ない場合があります。

## 安全・プライバシー

- 接続先の選択はユーザー操作で行います。
- 周辺機器を勝手に探索・操作したり、認証を回避したりする機能はありません。
- 書き込み前に確認ダイアログを表示します。
- 取得データやログを外部サーバーへ送信しません。
- 不明なCharacteristicへの書き込みは行わないでください。機器の動作に影響する可能性があります。

## 技術

HTML / CSS / JavaScript（単一ファイル）。Web Bluetooth APIを使用します。
