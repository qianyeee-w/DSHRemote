#!/usr/bin/env bash
# 生成 App 的正式发布密钥，并上传到 GitHub Secrets（m3-release.md T2）。
#
# 请在你自己打开的终端里运行，不要交给 agent 运行：密码只在这个终端里输入，不会出现在命令行参数、日志或对话中。
#
#   android/scripts/create-release-key.sh [密钥保存目录]      # 默认 ~/dshremote-release-key
#
# 需要：keytool（JDK 自带）、gh（已登录，对本仓库有写权限）、sha256sum、base64。
#
# 做的事：
#   1. 生成 PKCS12 密钥（RSA 4096，有效期 30 年，别名 dshremote），密钥密码和密钥库密码相同；
#   2. 上传三个 Secrets：ANDROID_KEYSTORE_BASE64、ANDROID_KEYSTORE_PASSWORD、ANDROID_KEY_ALIAS；
#   3. 把证书的 SHA-256 指纹（公开信息）写进 android/release-cert.sha256，发布流程用它检查 APK 的签名。
# 仓库里已经有发布密钥的 Secrets、本机又没有密钥文件时直接退出：换了密钥，已经安装的 App 就无法覆盖升级。
#
# 中途失败（例如上传时网络超时）后可以直接重新运行：密钥文件已经存在时不会重新生成，
# 只要求输入一次密码，用它打开已有的密钥文件确认无误后，继续上传（同一把密钥重复上传没有影响）。
set -euo pipefail

ALIAS=dshremote
REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
OUT_DIR=${1:-$HOME/dshremote-release-key}
KEYSTORE=$OUT_DIR/dshremote-release.p12
CERT_FILE=$REPO_ROOT/android/release-cert.sha256

fail() { echo "错误：$*" >&2; exit 1; }

# 网络不稳时重试几次；要上传的内容从文件或参数读，所以可以重复执行
retry() {
  local i
  for i in 1 2 3; do
    "$@" && return 0
    echo "失败，5 秒后重试（第 $i 次）……" >&2
    sleep 5
  done
  return 1
}

for cmd in keytool gh sha256sum base64; do
  command -v "$cmd" > /dev/null || fail "找不到 $cmd"
done
cd "$REPO_ROOT"
gh auth status > /dev/null 2>&1 || fail "gh 没有登录，先运行 gh auth login"
SECRETS=$(retry gh secret list --json name --jq '.[].name') || fail "读取仓库的 Secrets 失败，检查网络后重新运行"
if grep -qx ANDROID_KEYSTORE_BASE64 <<< "$SECRETS" && [ ! -e "$KEYSTORE" ]; then
  fail "仓库里已经有 ANDROID_KEYSTORE_BASE64，本机却没有密钥文件 $KEYSTORE。换密钥会让已安装的 App 无法覆盖升级；确实要换，先在 GitHub 上手动删除这三个 Secrets"
fi

if [ -e "$KEYSTORE" ]; then
  echo "密钥文件已经存在，不会重新生成，继续上传：$KEYSTORE"
  read -r -s -p "输入这个密钥的密码：" PASSWORD; echo
  export DSHREMOTE_KEY_PASSWORD=$PASSWORD
  keytool -list -keystore "$KEYSTORE" -storetype PKCS12 -alias "$ALIAS" -storepass:env DSHREMOTE_KEY_PASSWORD > /dev/null 2>&1 \
    || fail "密码不对，或者密钥文件里没有别名 $ALIAS"
else
  echo "密码至少 12 个字符，输入时不显示。这个密码和密钥文件要一起备份到密码管理器。"
  read -r -s -p "设置密钥密码：" PASSWORD; echo
  read -r -s -p "再输入一次：" AGAIN; echo
  [ "$PASSWORD" = "$AGAIN" ] || fail "两次输入的密码不一致"
  [ "${#PASSWORD}" -ge 12 ] || fail "密码太短"
  unset AGAIN
  export DSHREMOTE_KEY_PASSWORD=$PASSWORD

  mkdir -p "$OUT_DIR"
  chmod 700 "$OUT_DIR"
  keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -alias "$ALIAS" \
    -keyalg RSA -keysize 4096 -validity 10950 -dname "CN=DSHRemote" \
    -storepass:env DSHREMOTE_KEY_PASSWORD -keypass:env DSHREMOTE_KEY_PASSWORD > /dev/null
  chmod 600 "$KEYSTORE"
fi

# 证书（DER）的 SHA-256，和 apksigner verify --print-certs 输出的 certificate SHA-256 digest 相同
keytool -exportcert -keystore "$KEYSTORE" -storetype PKCS12 -alias "$ALIAS" -storepass:env DSHREMOTE_KEY_PASSWORD \
  | sha256sum | cut -d' ' -f1 > "$CERT_FILE"

upload_keystore() { base64 -w0 "$KEYSTORE" | gh secret set ANDROID_KEYSTORE_BASE64; }
upload_password() { printf '%s' "$PASSWORD" | gh secret set ANDROID_KEYSTORE_PASSWORD; }
retry upload_keystore || fail "上传 ANDROID_KEYSTORE_BASE64 失败。密钥文件已保存，检查网络后重新运行本脚本即可继续"
retry upload_password || fail "上传 ANDROID_KEYSTORE_PASSWORD 失败。检查网络后重新运行本脚本即可继续"
retry gh secret set ANDROID_KEY_ALIAS --body "$ALIAS" || fail "上传 ANDROID_KEY_ALIAS 失败。检查网络后重新运行本脚本即可继续"
unset PASSWORD DSHREMOTE_KEY_PASSWORD

cat <<EOF

完成：
  - 密钥文件：$KEYSTORE
  - 证书指纹（公开信息）已写入 android/release-cert.sha256：$(cat "$CERT_FILE")
  - 已上传 GitHub Secrets：ANDROID_KEYSTORE_BASE64、ANDROID_KEYSTORE_PASSWORD、ANDROID_KEY_ALIAS

接下来请你：
  1. 把密钥文件和密码一起备份到密码管理器（或其他离线的安全位置）。GitHub 上的 Secrets 无法读回，
     密钥丢了以后，新版本只能卸载重装，所有配对都要重做。
  2. 备份确认无误后删除本机的密钥文件：rm -r "$OUT_DIR"
  3. 告诉 agent「密钥已生成」，由它提交 android/release-cert.sha256。
EOF
