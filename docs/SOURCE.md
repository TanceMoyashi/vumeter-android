# ソースコードガイド

VUメーター（jp.himawari.vumeter）のソース構成と実装仕様の説明です。

## 技術構成

- 言語：Kotlin（フレームワークのみ使用。AndroidX / Jetpack Compose は不使用）
  ＋ AAudio キャプチャ用のネイティブライブラリ（C++ / JNI、arm64-v8a のみ）
- compileSdk 36 / targetSdk 36 / minSdk 31（Android 12以降）
- 依存ライブラリ：kotlin-stdlib のみ

## ファイル構成と役割

```
app/src/main/java/jp/himawari/vumeter/
├── MainActivity.kt  … 画面構築、入力デバイス選択、録音制御、トーン／ログ連携
├── VuEngine.kt      … 測定エンジン（VUバリスティクス、ラウドネス、相関、ピーク）
├── MeterView.kt     … 描画（MeterView / PeakBarView / CorrelationView）
├── AudioExtras.kt   … TonePlayer（校正トーン）、CsvLogger（CSVログ）
└── AAudioCapture.kt … AAudioエンジンのJNIラッパー
app/src/main/cpp/
└── aaudio_capture.cpp … AAudio入力ストリームとリングバッファ（ネイティブ）
app/src/main/
├── AndroidManifest.xml
└── res/             … アイコン、色定義、アダプティブアイコン定義
make_icon.py         … ランチャーアイコンの生成スクリプト
build-apk.sh         … 開発環境専用の手動ビルド手順（通常のビルドには不要）
```

### MainActivity.kt

- 画面全体をプログラムで構築（レイアウトXMLは使わない）
- 入力デバイスの一覧取得と切替。`AudioManager.getDevices` で列挙し、
  USB機器の抜き差しは `AudioDeviceCallback` で自動追従する
- AudioRecord の初期化はフォールバック順に試行する
  - 音源：UNPROCESSED → VOICE_RECOGNITION → MIC
  - サンプルレート：48 kHz → 44.1 kHz
  - チャンネル：ステレオ → モノラル
- `setPreferredDevice` で選択機器へルーティングし、実際に使われている機器
  （`routedDevice`）や音源種別をステータス欄に表示する
- 画面回転時は `onConfigurationChanged` でメーター表示を切り替える（測定は継続）

### VuEngine.kt（測定エンジン）

**VUバリスティクス（IEC 60268-17）**
- 整流信号を駆動源とする2次系モデルで針の動きを再現する
- 定数：ζ = 0.813、ω = 13.5 rad/s
- 検証済みの特性：基準レベルのトーン急加で 99% 到達 300.5 ms（規格 300 ms）、
  オーバーシュート 1.24%（規格 1.0〜1.5%）
- 校正：正弦波の平均整流値（2/π）を基準とし、基準レベルの正弦波が 0 VU を指す

**ラウドネス（ITU-R BS.1770）**
- K特性フィルタの係数は実サンプルレートから一般式で算出
  （48 kHz では公開値と一致することを確認済み）
- Momentary（400 ms）、Short-term（3 s）、Integrated（ゲーティング付き）を表示
- 近似点：Integrated のゲーティングは規格の 400 ms ブロック（75%オーバーラップ）を
  100 ms ブロックで近似している
- 目安：1 kHz 正弦波 -18 dBFS（ステレオ）で -18 LUFS 近傍を示す

**位相相関**
- 1秒窓で L/R の相関係数を算出し、-1〜+1 で表示する

**ピーク**
- 瞬時ピークとホールドピーク（ホールド 1.5 秒＋20 dB/s で減衰）を保持
- 「ピークリセット」はラウドネスの積算もリセットする

### MeterView.kt（描画）

- MeterView：アナログ風ダイヤル。目盛り配置は電圧比ベース、%スケール併記、
  0〜+3 VU は赤ゾーン。主要目盛りは -20, -10, -7, -5, -3, -2, -1, 0, +1, +2, +3
- 縦画面では L+R 平均（針の振れ＝電圧を線形領域で平均してから VU 化）、
  横画面では L（黒針）/ R（赤針）の2メーター
- PeakBarView：dBFS のピークバー（緑 → 黄（-9 dB〜）→ 赤（-3 dB〜））、ホールドマーカー付き
- CorrelationView：相関バーと数値

### AudioExtras.kt

- TonePlayer：1kHz 校正トーン。出力レベルは基準レベル設定に連動する。
  出力先は、入力に選んだUSB機器と同名のUSB出力を優先し、なければ既定出力を使う
- CsvLogger：測定中のみ約200 ms 間隔で記録し、MediaStore 経由で端末の
  「ダウンロード」フォルダに `vu_log_yyyyMMdd_HHmmss.csv` として保存する
  （失敗時はアプリ専用フォルダにフォールバック）
  - 列：時刻（ISO）/ 経過秒 / VU L,R / 瞬時ピーク / ホールドピーク /
    ラウドネス M,S,I / 相関 / クリップ / 基準レベル

## 録音エンジン（AudioRecord / AAudio）

画面の「録音エンジン」で2方式を切り替えられる。測定エンジン（VuEngine）へは
どちらもインターリーブ済み float（-1..1）を渡すため、表示や測定結果の扱いは共通。

- **AudioRecord**：従来方式。音源（UNPROCESSED → VOICE_RECOGNITION → MIC）、
  サンプルレート（48k → 44.1k）、チャンネル（ステレオ → モノラル）の順に
  フォールバックしながら開始する
- **AAudio**（`AAudioCapture.kt` + `cpp/aaudio_capture.cpp`）：
  ネイティブの低レイテンシAPI。SHAREDモード＋LOW_LATENCYで入力ストリームを開き、
  データコールバックが約1秒分のリングバッファへ書き込み、Kotlin側が読み出す
  - フォーマットは PCM_FLOAT を優先し、不可なら PCM_I16（コールバック内で変換）
  - 入力デバイスは AudioManager のデバイスIDで指定する
  - プリセットは UNPROCESSED → VOICE_RECOGNITION → GENERIC の順に試行
  - リングバッファが溢れた場合は古いフレームから捨て、ドロップ数を数える。
    停止時に XRun 回数とドロップ数があればステータスに表示する
  - 開始できなかった場合は AudioRecord に自動で切り替わり、その旨がステータスに出る
  - ネイティブライブラリ（libaaudio_capture.so）は arm64-v8a のみビルドして同梱する

## 基準レベル

0 VU に対応するデジタルレベルを -24 / -20 / -18 / -16 / -14 dBFS から選択する
（デフォルト -18 dBFS）。校正トーンの出力レベルもこの設定に連動する。

## アイコンの再生成

アイコンの PNG は生成物のためリポジトリには含めていない。
再生成は `python3 make_icon.py` で行い、`app/src/main/res/` 以下に
各解像度の PNG とアダプティブアイコン前景が書き出される。

## ビルド

通常は Android Studio でプロジェクトを開いてビルドする。
`build-apk.sh` は開発環境（Gradleデーモンが使えない環境）専用の手動手順で、
参考資料として同梱している。AAudio のネイティブライブラリのビルドには
Android NDK が必要で、生成された .so はリポジトリには含めていない
（`build-apk.sh` 内の clang 実行で再生成される）。

## 署名について

リリースAPKの署名キー（`vumeter.keystore`）はセキュリティのため
リポジトリに含めていない。アップデート版の配布には同じ署名キーが必要。
詳細は [リリース手順](RELEASE.md) を参照。
