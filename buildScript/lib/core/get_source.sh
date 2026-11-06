#!/bin/bash
set -e

source "buildScript/init/env.sh"
ENV_NB4A=1

####

# nb4a.properties 是整个项目唯一的 sing-box 版本源。
SINGBOX_VERSION_LINES=$(grep -c '^SINGBOX_VERSION=' nb4a.properties || true)
if [ "$SINGBOX_VERSION_LINES" -ne 1 ]; then
  echo ">> ERROR: expected exactly one SINGBOX_VERSION in nb4a.properties, found $SINGBOX_VERSION_LINES" >&2
  exit 1
fi
SINGBOX_VERSION=$(grep '^SINGBOX_VERSION=' nb4a.properties | cut -d'=' -f2 | tr -d '\r[:space:]')
if ! printf '%s' "$SINGBOX_VERSION" | grep -qE '^v[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.-]+)?$'; then
  echo ">> ERROR: invalid SINGBOX_VERSION tag: $SINGBOX_VERSION" >&2
  exit 1
fi
echo ">> Using official sing-box $SINGBOX_VERSION"

OFFICIAL_REPO="https://github.com/SagerNet/sing-box.git"

pushd ..

if [ ! -d "sing-box" ]; then
  git clone --depth 1 --branch "$SINGBOX_VERSION" "$OFFICIAL_REPO" sing-box
else
  pushd sing-box
  # 无条件校正 origin，避免名称相似的 fork 或旧 remote 绕过检查。
  git remote set-url origin "$OFFICIAL_REPO"
  # 强制刷新目标 tag；若上游 tag 被移动，本地不能继续使用陈旧 tag。
  git fetch --force --depth 1 origin "+refs/tags/$SINGBOX_VERSION:refs/tags/$SINGBOX_VERSION" || \
    git fetch --force origin "+refs/tags/$SINGBOX_VERSION:refs/tags/$SINGBOX_VERSION"
  git checkout --detach --force "$SINGBOX_VERSION"
  popd
fi

pushd sing-box
EXPECTED_COMMIT=$(git rev-parse "$SINGBOX_VERSION^{commit}")
ACTUAL_COMMIT=$(git rev-parse HEAD)
ACTUAL_ORIGIN=$(git remote get-url origin)
if [ "$ACTUAL_ORIGIN" != "$OFFICIAL_REPO" ]; then
  echo ">> ERROR: sing-box origin mismatch: $ACTUAL_ORIGIN" >&2
  exit 1
fi
if [ "$ACTUAL_COMMIT" != "$EXPECTED_COMMIT" ]; then
  echo ">> ERROR: sing-box HEAD $ACTUAL_COMMIT does not match $SINGBOX_VERSION ($EXPECTED_COMMIT)" >&2
  exit 1
fi
echo ">> Verified official sing-box $SINGBOX_VERSION at $ACTUAL_COMMIT"
popd

popd
