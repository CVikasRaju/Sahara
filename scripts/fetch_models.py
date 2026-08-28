#!/usr/bin/env python3
"""
Downloads open-source STT/TTS/VAD model checkpoints for iTantra.

This script requires internet access to huggingface.co, which is NOT reachable
from a sandboxed build/CI environment with restricted egress — run it on your
own development machine, not inside a locked-down container.

Usage:
    python scripts/fetch_models.py --lang hi,kn --output assets/models_raw

Before running, verify each model's license per docs/MODEL_LICENSES.md — this
script does not check licenses for you.
"""

import argparse
import os
import sys

try:
    from huggingface_hub import snapshot_download
except ImportError:
    print("Missing dependency. Run: pip install huggingface_hub", file=sys.stderr)
    sys.exit(1)

# NOTE: verify these repo IDs are current and correctly licensed before use —
# AI4Bharat periodically reorganizes model repos. Cross-check against
# https://ai4bharat.iitm.ac.in/ and the model's Hugging Face card, and fill in
# docs/MODEL_LICENSES.md with the confirmed license for each one you actually use.
STT_REPOS = {
    "indic_conformer": "ai4bharat/indicconformer",
    "indic_whisper": "ai4bharat/indicwhisper",
}

TTS_REPOS = {
    "indic_tts": "ai4bharat/vits_rasa_13",
}

VAD_REPO = "onnx-community/silero-vad"

SUPPORTED_LANGS = ["hi", "gu", "mr", "kn", "ta", "te", "ml", "or", "bn", "en-IN"]


def download_repo(repo_id: str, target_dir: str, allow_patterns=None):
    os.makedirs(target_dir, exist_ok=True)
    print(f"Downloading {repo_id} -> {target_dir}")
    try:
        snapshot_download(
            repo_id=repo_id,
            local_dir=target_dir,
            allow_patterns=allow_patterns,
        )
        print(f"  done: {repo_id}")
    except Exception as e:
        print(f"  FAILED: {repo_id}: {e}", file=sys.stderr)
        if "401" in str(e) or "restricted" in str(e) or "gated" in str(e):
            print("  [HuggingFace Auth Tip] This model repository is gated or restricted. "
                  "Log in with `hf.exe login` or set the `HF_TOKEN` environment variable "
                  "with a valid Hugging Face token.", file=sys.stderr)
        print("  Check the repo ID is current on huggingface.co — model repo names "
              "change over time and this script's defaults may be stale.", file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lang", required=True,
                         help=f"Comma-separated language codes to fetch, from: {','.join(SUPPORTED_LANGS)}")
    parser.add_argument("--output", default="assets/models_raw",
                         help="Output directory for raw (unquantized) model files")
    parser.add_argument("--stt-model", default="indic_conformer", choices=STT_REPOS.keys())
    parser.add_argument("--tts-model", default="indic_tts", choices=TTS_REPOS.keys())
    parser.add_argument("--skip-vad", action="store_true")
    args = parser.parse_args()

    langs = [l.strip() for l in args.lang.split(",")]
    for l in langs:
        if l not in SUPPORTED_LANGS:
            print(f"Unsupported language code: {l}. Supported: {SUPPORTED_LANGS}", file=sys.stderr)
            sys.exit(1)

    if not args.skip_vad:
        download_repo(VAD_REPO, os.path.join(args.output, "vad"))

    for lang in langs:
        download_repo(
            STT_REPOS[args.stt_model],
            os.path.join(args.output, "stt", lang),
        )
        download_repo(
            TTS_REPOS[args.tts_model],
            os.path.join(args.output, "tts", lang),
        )

    print("\nNext steps:")
    print("  1. Fill in / verify docs/MODEL_LICENSES.md for every model just downloaded.")
    print("  2. Run scripts/quantize_models.py to produce INT8 ONNX files.")
    print("  3. Run scripts/benchmark_wer.py against a held-out test set (Vistaar/Kathbath).")


if __name__ == "__main__":
    main()
