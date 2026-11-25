#!/usr/bin/env bash
# Lädt die Modelle für die Emulator-Tests nach e2e-models/. Aufruf: download_models.sh <kompakt|standard>
set -e
SET=$1
C="curl -fsSL --retry 6 --retry-all-errors --retry-delay 10"
GH=https://github.com/k2-fsa/sherpa-onnx/releases/download
HF=https://huggingface.co
mkdir -p e2e-models tmp && cd tmp
if [ "$SET" = kompakt ]; then LLM=$HF/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf
else LLM=$HF/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q3_K_M.gguf; fi
PK=sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8
$C $GH/asr-models/$PK.tar.bz2 | tar xj
for f in encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt; do cp $PK/$f ../e2e-models/parakeet-$f; done
$C $GH/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2 | tar xj
cp sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx ../e2e-models/pyannote-segmentation-3-0.int8.onnx
$C -o ../e2e-models/wespeaker_en_voxceleb_resnet34_LM.onnx $GH/speaker-recongition-models/wespeaker_en_voxceleb_resnet34_LM.onnx
$C -o ../e2e-models/$(basename $LLM) $LLM
cd .. && rm -rf tmp
ls -lh e2e-models
