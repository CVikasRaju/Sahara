#!/usr/bin/env python3
"""
Computes Word Error Rate (WER) for STT output against ground-truth transcripts —
see docs/ML_PIPELINE.md §7 and docs/TESTING.md §1.

Requires: pip install jiwer

Expects a manifest CSV with columns: audio_path,reference_text
(Build this from AI4Bharat Vistaar or Kathbath held-out test splits — see
docs/README.md "Where to get open-source Indic speech test data" discussion.)

This script does NOT run your STT model for you — plug in your inference call
in `transcribe()` below (either a Sherpa-ONNX Python binding call, for offline
pre-deployment validation on your dev machine, or read pre-generated hypothesis
transcripts from a second CSV column via --hypotheses-csv).

Usage:
    python scripts/benchmark_wer.py --manifest test_manifest.csv --lang hi
    python scripts/benchmark_wer.py --manifest test_manifest.csv --hypotheses-csv hyps.csv
"""

import argparse
import csv
import sys

try:
    import jiwer
except ImportError:
    print("Missing dependency. Run: pip install jiwer", file=sys.stderr)
    sys.exit(1)


def transcribe(audio_path: str, lang: str) -> str:
    """
    TODO: replace this stub with a real call to your quantized STT model.
    For a Sherpa-ONNX Python binding (useful for pre-deployment validation
    before you've wired the Android JNI layer):

        import sherpa_onnx
        recognizer = sherpa_onnx.OfflineRecognizer.from_transducer(...)
        stream = recognizer.create_stream()
        stream.accept_waveform(sample_rate, samples)
        recognizer.decode_stream(stream)
        return stream.result.text

    Left unimplemented here because it depends on which exact model/runtime
    you land on (IndicConformer vs IndicWhisper, Sherpa-ONNX vs whisper.cpp).
    """
    raise NotImplementedError(
        "Wire this function to your actual STT inference call, or use "
        "--hypotheses-csv to supply pre-generated transcripts instead."
    )


def load_manifest(path: str):
    rows = []
    with open(path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            rows.append((row["audio_path"], row["reference_text"]))
    return rows


def load_hypotheses(path: str) -> dict:
    mapping = {}
    with open(path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            mapping[row["audio_path"]] = row["hypothesis_text"]
    return mapping


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, help="CSV with audio_path,reference_text")
    parser.add_argument("--lang", default=None, help="Language code, for logging only")
    parser.add_argument("--hypotheses-csv", default=None,
                         help="Optional CSV with audio_path,hypothesis_text — skips calling transcribe()")
    args = parser.parse_args()

    manifest = load_manifest(args.manifest)
    hypotheses = load_hypotheses(args.hypotheses_csv) if args.hypotheses_csv else None

    references = []
    hyps = []
    skipped = 0

    for audio_path, reference_text in manifest:
        try:
            hyp = hypotheses[audio_path] if hypotheses is not None else transcribe(audio_path, args.lang)
        except (KeyError, NotImplementedError) as e:
            skipped += 1
            continue
        references.append(reference_text)
        hyps.append(hyp)

    if not references:
        print("No utterances scored — either supply --hypotheses-csv, or implement "
              "transcribe() with your real model.", file=sys.stderr)
        sys.exit(1)

    wer = jiwer.wer(references, hyps)
    cer = jiwer.cer(references, hyps)

    print(f"Language: {args.lang or 'unspecified'}")
    print(f"Utterances scored: {len(references)} (skipped: {skipped})")
    print(f"WER: {wer * 100:.2f}%")
    print(f"CER: {cer * 100:.2f}%")
    print(f"\nTarget from docs/README.md §5: WER < 15% (stretch: <12%)")
    if wer > 0.15:
        print("=> Above target. If this is the post-quantization number, compare "
              "against the FP32 baseline to see how much quantization cost you, "
              "per docs/ML_PIPELINE.md §3.")


if __name__ == "__main__":
    main()
