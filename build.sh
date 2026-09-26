#!/usr/bin/env bash
# 编译红果灰屏修复 LSPosed 模块（需要 Git Bash / WSL，Java 17+，Android SDK）
# 用法: ./build.sh [版本号]   环境变量 JAVA_HOME / ANDROID_SDK 可覆盖默认值
set -e
cd "$(dirname "$0")"

: "${JAVA_HOME:=D:/Android/Android Studio/jbr}"
: "${ANDROID_SDK:=D:/Android/Sdk}"
BT="$ANDROID_SDK/build-tools/34.0.0"
AJ="$ANDROID_SDK/platforms/android-34/android.jar"
VERSION="${1:-1.1}"
APK="hongguofix-$VERSION.apk"

# 签名 keystore 不入库，首次编译自动生成
if [ ! -f hg.keystore ]; then
    echo "==> 生成签名 keystore"
    "$JAVA_HOME/bin/keytool.exe" -genkeypair -keystore hg.keystore -alias hgfix \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass hongguofix -keypass hongguofix -dname "CN=hongguofix"
fi

echo "==> javac"
rm -rf classes build
mkdir -p classes build
"$JAVA_HOME/bin/javac.exe" --release 11 -cp "$AJ" -d classes \
    $(find source/java source/stubs -name "*.java")
echo "javac ok"

echo "==> d8"
# 注意：所有 hook 回调必须保持顶层类（匿名内部类会触发 build-tools 34 d8 的 NPE bug）
# 直接用 java 调 d8.jar，绕开 d8.bat 的静默失败
"$JAVA_HOME/bin/java.exe" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --release --min-api 27 --lib "$AJ" --output build $(find classes/com -name "*.class")
[ -f build/classes.dex ] || { echo "d8 produced no classes.dex"; exit 1; }
echo "d8 ok"

echo "==> aapt2 link"
"$BT/aapt2.exe" link -o build/base.apk -I "$AJ" --manifest source/AndroidManifest.xml \
    -A source/assets --min-sdk-version 27 --target-sdk-version 34
echo "aapt2 ok"

echo "==> 打包 dex / 对齐 / 签名"
(cd build && "$JAVA_HOME/bin/jar.exe" -uf base.apk classes.dex)
"$BT/zipalign.exe" -f 4 build/base.apk build/aligned.apk
"$BT/apksigner.bat" sign --ks hg.keystore --ks-pass pass:hongguofix --out "build/$APK" build/aligned.apk
"$BT/apksigner.bat" verify "build/$APK"
echo "==> 完成: build/$APK"
