#!/bin/bash
# Manual APK build (Gradle daemon is unavailable in this environment).
# Requires: JDK 17 (~/tools/jdk), Android SDK (~/tools/sdk), kotlinc (~/tools/kotlinc)
set -e
export JAVA_HOME=~/tools/jdk
export PATH=$JAVA_HOME/bin:$PATH
BT=~/tools/sdk/build-tools/36.0.0
AJAR=~/tools/sdk/platforms/android-36/android.jar
SRC=~/workspace/vumeter
OUT=/tmp/vubuild
mkdir -p $OUT/dex
~/tools/kotlinc/bin/kotlinc -classpath $AJAR -d $OUT/classes.jar $SRC/app/src/main/java/jp/himawari/vumeter/*.kt
$BT/d8 --release --lib $AJAR --output $OUT/dex $OUT/classes.jar ~/tools/kotlinc/lib/kotlin-stdlib.jar
$BT/aapt2 compile --dir $SRC/app/src/main/res -o $OUT/res.zip
$BT/aapt2 link -o $OUT/base.apk -I $AJAR --manifest $SRC/app/src/main/AndroidManifest.xml \
  --min-sdk-version 31 --target-sdk-version 36 --version-code 5 --version-name 1.4 \
  $OUT/res.zip
(cd $OUT/dex && $BT/aapt add ../base.apk classes.dex)
$BT/zipalign -p -f 4 $OUT/base.apk $OUT/aligned.apk
$BT/apksigner sign --ks $SRC/vumeter.keystore --ks-pass pass:vumeter --key-pass pass:vumeter \
  --out $OUT/VuMeter.apk $OUT/aligned.apk
cp $OUT/VuMeter.apk ~/workspace/your_files/VuMeter.apk
echo "Built: ~/workspace/your_files/VuMeter.apk"
