# サードパーティライセンス

VUメーターの作成・配布に関わる第三者コンポーネントと、そのライセンスの確認結果です。
（2026-10-31 確認）

## 確認結果の要約

- アプリのソースコードは全て新規に作成したもので、外部のコードを複製した部分はない
- APK に同梱している第三者コンポーネントは **Kotlin 標準ライブラリのみ** で、
  そのライセンス（Apache License 2.0）が求める帰属表示を本ファイルで行う
- 上記の範囲で、第三者のライセンスに抵触する要素は見当たらなかった

## APK に同梱しているもの

### Kotlin standard library (kotlin-stdlib 2.2.0)

- 提供元：JetBrains s.r.o.（Kotlin プロジェクト）
- ライセンス：Apache License, Version 2.0
- ライセンス全文：https://www.apache.org/licenses/LICENSE-2.0
- 備考：APK 内にクラスライブラリとして同梱。再配布にあたっては
  本ファイルによる帰属表示を行っている

## リンクのみで同梱していないもの

### AAudio（Android OS のシステムライブラリ）

- 提供元：The Android Open Source Project
- ライセンス：Apache License, Version 2.0
- 備考：端末の OS が持つ共有ライブラリを動的リンクで利用しているだけで、
  APK には同梱していない。ビルド時に使う NDK のヘッダも同じライセンス

### Android プラットフォーム API（android.jar）

- 提供元：The Android Open Source Project
- ライセンス：Apache License, Version 2.0
- 備考：ビルド時のコンパイルにのみ使用し、配布物には含まれない

## ビルド・生成にのみ使ったもの

### DejaVu Sans フォント

- 提供元：Bitstream, Inc. / DejaVu プロジェクト
- ライセンス：Bitstream Vera Fonts License（DejaVu の変更部分はパブリックドメイン）
- 備考：ランチャーアイコンの文字描画に使用。フォント自体は配布しておらず、
  描画結果の画像のみをアプリに含めている

### Python Imaging Library (Pillow)

- ライセンス：HPND License（Historical Permission Notice and Disclaimer）
- 備考：アイコン生成スクリプトの実行にのみ使用。配布物には含まれない
