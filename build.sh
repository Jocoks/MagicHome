#!/system/bin/sh
# MagicHome LSPosed 模块：设备内构建脚本（Termux 环境，无需 Android Studio）
set -e

P=${PREFIX:-/data/data/com.termux/files/usr}
if [ ! -x "$P/lib/jvm/java-17-openjdk/bin/javac" ]; then
  echo "ERROR: 找不到 javac。请设置 PREFIX 指向你的 Termux 前缀，例如：" >&2
  echo "  export PREFIX=/data/data/com.termux/files/usr   # 普通 Termux" >&2
  exit 1
fi
export PATH="$P/lib/jvm/java-17-openjdk/bin:$P/bin:/system/bin:/system/xbin:$PATH"
ROOT=$(cd "$(dirname "$0")" && pwd)
TOOLS="$ROOT/tools"
BUILD="$ROOT/build"

cd "$ROOT"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex"

echo "[1/5] javac"
javac -source 8 -target 8 -nowarn \
    -classpath "$TOOLS/android.jar:$TOOLS/libxposed-api.jar" \
    -d "$BUILD/classes" \
    $(find src -name '*.java')

echo "[2/5] d8 -> classes.dex"
CLASSES=$(find "$BUILD/classes" -name '*.class')
java -cp "$P/share/java/d8.jar" com.android.tools.r8.D8 \
    --lib "$TOOLS/android.jar" --min-api 29 \
    --output "$BUILD/dex" $CLASSES

echo "[3/5] aapt2 link"
"$P/bin/aapt2" link \
    -o "$BUILD/base.apk" \
    -I /system/framework/framework-res.apk \
    --manifest "$ROOT/AndroidManifest.xml"

echo "[4/5] pack"
python3 "$TOOLS/pack.py" "$BUILD/base.apk" "$BUILD/unsigned.apk" "$BUILD/dex/classes.dex" "$ROOT/meta"

echo "[5/5] sign"
if [ ! -f "$TOOLS/debug.keystore" ]; then
    keytool -genkeypair -keystore "$TOOLS/debug.keystore" \
        -storepass android -keypass android -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
mkdir -p "$BUILD/tmp"
java -Djava.io.tmpdir="$BUILD/tmp" -jar "$P/share/java/apksigner.jar" sign \
    --ks "$TOOLS/debug.keystore" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey --min-sdk-version 29 \
    --v1-signing-enabled true --v2-signing-enabled true \
    "$BUILD/unsigned.apk"

cp "$BUILD/unsigned.apk" "$BUILD/MagicHome.apk"
echo "OK -> $BUILD/MagicHome.apk"
ls -l "$BUILD/MagicHome.apk"
