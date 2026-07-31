#!/usr/bin/env bash
# Shared Java runtime selection for LiteRT-LM builds.

thinktank_java_major() {
  local java_home="$1"
  local version_line version major
  [[ -x "$java_home/bin/java" ]] || return 1
  version_line="$("$java_home/bin/java" -version 2>&1 | head -n 1)"
  version="${version_line#*\"}"
  version="${version%%\"*}"
  major="${version%%.*}"
  if [[ "$major" == "1" ]]; then
    major="${version#1.}"
    major="${major%%.*}"
  fi
  [[ "$major" =~ ^[0-9]+$ ]] || return 1
  printf '%s\n' "$major"
}

thinktank_require_java21() {
  local candidate major
  local -a candidates=()
  [[ -n "${THINKTANK_JAVA_HOME:-}" ]] && candidates+=("$THINKTANK_JAVA_HOME")
  [[ -n "${JAVA_HOME:-}" ]] && candidates+=("$JAVA_HOME")
  candidates+=(
    "$HOME/.local/cbot-tools/jdk-21.0.11+10/Contents/Home"
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  )

  for candidate in "${candidates[@]}"; do
    major="$(thinktank_java_major "$candidate" 2>/dev/null || true)"
    if [[ -n "$major" && "$major" -ge 21 ]]; then
      export JAVA_HOME="$candidate"
      export PATH="$JAVA_HOME/bin:$PATH"
      return 0
    fi
  done

  echo "Java 21+ is required for LiteRT-LM 0.14.0." >&2
  echo "Set THINKTANK_JAVA_HOME or install Temurin 21 at:" >&2
  echo "  $HOME/.local/cbot-tools/jdk-21.0.11+10/Contents/Home" >&2
  return 1
}
