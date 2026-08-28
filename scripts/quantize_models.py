#!/usr/bin/env python3
"""
Quantizes FP32 ONNX models to INT8 for mobile deployment — see docs/ML_PIPELINE.md §3.

Requires: pip install onnx onnxruntime

Usage:
    python scripts/quantize_models.py --input assets/models_raw --output assets/models

This performs dynamic quantization only (weight-only INT8). It does NOT validate
WER post-quantization — run scripts/benchmark_wer.py separately afterward and
compare against the FP32 baseline before shipping, per docs/ML_PIPELINE.md §3
step 3 ("do not assume quantization is free").
"""

import argparse
import os
import sys

try:
    from onnxruntime.quantization import quantize_dynamic, QuantType
except ImportError:
    print("Missing dependency. Run: pip install onnx onnxruntime", file=sys.stderr)
    sys.exit(1)


def compress_model(input_path: str, output_path: str) -> None:
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    print(f"Quantizing: {input_path}")
    quantize_dynamic(
        model_input=input_path,
        model_output=output_path,
        weight_type=QuantType.QInt8,
    )
    orig = os.path.getsize(input_path) / (1024 * 1024)
    quant = os.path.getsize(output_path) / (1024 * 1024)
    reduction = (1 - quant / orig) * 100 if orig > 0 else 0
    print(f"  {orig:.1f} MB -> {quant:.1f} MB  ({reduction:.1f}% reduction)")


def find_onnx_files(root: str):
    for dirpath, _, filenames in os.walk(root):
        for f in filenames:
            if f.endswith(".onnx") and ".int8." not in f:
                yield os.path.join(dirpath, f)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", default="assets/models_raw",
                         help="Directory of raw FP32 .onnx files (from fetch_models.py)")
    parser.add_argument("--output", default="assets/models",
                         help="Directory to write INT8 quantized models (matches app/src/main/assets/models layout)")
    args = parser.parse_args()

    if not os.path.isdir(args.input):
        print(f"Input directory not found: {args.input}. Run fetch_models.py first.", file=sys.stderr)
        sys.exit(1)

    found_any = False
    for onnx_path in find_onnx_files(args.input):
        found_any = True
        rel = os.path.relpath(onnx_path, args.input)
        out_path = os.path.join(args.output, rel.replace(".onnx", ".int8.onnx"))
        compress_model(onnx_path, out_path)

    if not found_any:
        print(f"No .onnx files found under {args.input} — did fetch_models.py complete successfully?")
        sys.exit(1)

    print("\nNext step: verify tokenizer/vocab files (tokens.txt) match each quantized "
          "model exactly before integration — see docs/ML_PIPELINE.md §6. A mismatched "
          "tokenizer produces confident-looking garbled output, not an obvious crash.")


if __name__ == "__main__":
    main()
