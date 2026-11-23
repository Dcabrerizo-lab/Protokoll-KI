#!/usr/bin/env bash
# Baut die LLM-JNI-Bibliothek für Linux (nur für den Qualitätstest in der CI).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LLAMA="$ROOT/app/src/main/cpp/llama.cpp"
OUT="$ROOT/build-host"
cmake -S "$LLAMA" -B "$OUT/llama" -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_COMMON=OFF -DLLAMA_CURL=OFF \
  -DLLAMA_OPENSSL=OFF -DGGML_NATIVE=ON -DGGML_OPENMP=OFF
cmake --build "$OUT/llama" -j"$(nproc)" --target llama
JH="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(which java)")")")}"
LIBS=$(find "$OUT/llama" -name "*.a" | sort -r | tr '\n' ' ')
g++ -std=c++17 -O2 -fPIC -shared -I"$ROOT/scripts/host-stub" -I"$JH/include" -I"$JH/include/linux" \
  -I"$LLAMA/include" -I"$LLAMA/ggml/include" "$ROOT/app/src/main/cpp/llm_jni.cpp" \
  -Wl,--start-group $LIBS -Wl,--end-group -lpthread -ldl -o "$OUT/libprotokollki_llm.so"
echo "$OUT/libprotokollki_llm.so"
