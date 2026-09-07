#!/bin/bash

set -e

DIR=app/src/main/assets/sing-box
rm -rf $DIR
mkdir -p $DIR
cd $DIR

get_latest_release() {
  local repo="$1"
  local tag=""
  # Try redirect location first (no API rate limiting)
  tag=$(curl -sI "https://github.com/$repo/releases/latest" | grep -i '^location:' | sed -E 's/.*\/tag\/([^\r\n]+).*/\1/' | tr -d '\r\n')
  # If empty, try GitHub API with token if available
  if [ -z "$tag" ]; then
    if [ -n "$GITHUB_TOKEN" ]; then
      tag=$(curl -s -H "Authorization: Bearer $GITHUB_TOKEN" "https://api.github.com/repos/$repo/releases/latest" | grep '"tag_name":' | sed -E 's/.*"([^"]+)".*/\1/' | tr -d '\r\n')
    else
      tag=$(curl -s "https://api.github.com/repos/$repo/releases/latest" | grep '"tag_name":' | sed -E 's/.*"([^"]+)".*/\1/' | tr -d '\r\n')
    fi
  fi
  echo "$tag"
}

####
VERSION_GEOIP=$(get_latest_release "SagerNet/sing-geoip")
echo VERSION_GEOIP=$VERSION_GEOIP
if [ -n "$VERSION_GEOIP" ]; then
  echo -n "$VERSION_GEOIP" > geoip.version.txt
  curl -fLSso geoip.db "https://github.com/SagerNet/sing-geoip/releases/download/$VERSION_GEOIP/geoip.db" || curl -fLSso geoip.db "https://github.com/SagerNet/sing-geoip/releases/latest/download/geoip.db"
else
  echo -n "latest" > geoip.version.txt
  curl -fLSso geoip.db "https://github.com/SagerNet/sing-geoip/releases/latest/download/geoip.db"
fi
xz -9 geoip.db

####
VERSION_GEOSITE=$(get_latest_release "SagerNet/sing-geosite")
echo VERSION_GEOSITE=$VERSION_GEOSITE
if [ -n "$VERSION_GEOSITE" ]; then
  echo -n "$VERSION_GEOSITE" > geosite.version.txt
  curl -fLSso geosite.db "https://github.com/SagerNet/sing-geosite/releases/download/$VERSION_GEOSITE/geosite.db" || curl -fLSso geosite.db "https://github.com/SagerNet/sing-geosite/releases/latest/download/geosite.db"
else
  echo -n "latest" > geosite.version.txt
  curl -fLSso geosite.db "https://github.com/SagerNet/sing-geosite/releases/latest/download/geosite.db"
fi
xz -9 geosite.db
