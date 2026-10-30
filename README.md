# VUメーター (VuMeter)

Android向けVUメーターアプリ。本体マイク／USBオーディオインターフェース入力に対応。

- パッケージ名: `jp.himawari.vumeter`
- 対応: Android 12 (API 31) 以降 / Pixel 10 (Android 17) で設計・動作想定
- 現在のバージョン: v1.5

## このアプリについて

- 本アプリは、AIアシスタント **Muse** によって作成されたものです
- 実験的に作られたアプリであり、動作・品質・測定精度について保証するものではありません
- **利用は自己責任でお願いします。** 本アプリの使用によって生じたいかなる損害についても、
  作成者は責任を負いません
- 今後の更新やバグ対応についても保証はありません

## ライセンスについて

- アプリのソースコードは全て新規に作成したもので、外部コードの複製はありません
- APKに同梱している第三者コンポーネントは Kotlin 標準ライブラリのみ
  （Apache License 2.0）で、確認した範囲では第三者のライセンスに
  抵触する要素は見当たりませんでした
- 依存コンポーネントとライセンスの詳細は
  [サードパーティライセンス](docs/THIRD-PARTY-LICENSES.md) を参照してください

## 機能

- VUバリスティクスは IEC 60268-17 準拠（0 VU到達 300ms、オーバーシュート約1.2%）
- 基準レベル切替: -24 / -20 / -18 / -16 / -14 dBFS（デフォルト -18 dBFS）
- 縦画面: L+R平均の1メーター / 横画面: L・Rの2メーター表示
- ピークバー（ホールド付き）、OVERランプ
- ラウドネス表示: ITU-R BS.1770（Momentary / Short-term / Integrated）
- 位相相関メーター
- 校正用 1kHz トーン出力（基準レベルちょうど）
- 測定値のCSVログ（ダウンロードフォルダに保存）
- 録音エンジン切替: AudioRecord / AAudio（ネイティブ低レイテンシ）

## ソース構成

- `app/src/main/java/jp/himawari/vumeter/` — Kotlinソース
  （MainActivity / VuEngine / MeterView / AudioExtras / AAudioCapture）
- `app/src/main/cpp/` — AAudioキャプチャのネイティブソース（C++）
- `app/src/main/AndroidManifest.xml`, `app/src/main/res/` — マニフェストとリソース
- `make_icon.py` — ランチャーアイコンの再生成スクリプト
  （アイコンPNGは生成物のためリポジトリには含めていません。このスクリプトで再生成できます）
- `build-apk.sh` — この開発環境向けの手動ビルド手順スクリプト
  （Gradleデーモンが使えない環境用の参考手順。通常はAndroid Studioで開けます）

## ドキュメント

- [ソースコードガイド](docs/SOURCE.md) — 構成、各モジュールの役割、測定仕様（VUバリスティクス／BS.1770）
- [リリース手順](docs/RELEASE.md) — バージョンの付け方、ビルド・署名、ReleasesへのAPK公開手順
- [変更履歴](CHANGELOG.md) — バージョンごとの変更内容
- [サードパーティライセンス](docs/THIRD-PARTY-LICENSES.md) — 依存コンポーネントとライセンスの確認結果

## 署名について

リリースAPKの署名キー（`vumeter.keystore`）はセキュリティのためリポジトリに含めていません。
アップデート版を配布する場合は、同じ署名キーを保持している環境でビルドしてください。
