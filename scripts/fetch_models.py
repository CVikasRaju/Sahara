#!/usr/bin/env python3
"""
Downloads open-source STT/TTS/VAD model checkpoints for iTantra.
"""

import argparse
import os
import sys

try:
    from huggingface_hub import snapshot_download
except ImportError:
    print("Missing dependency. Run: pip install huggingface_hub", file=sys.stderr)
    sys.exit(1)

# STT: Public Sherpa-ONNX model
STT_REPO = "meetsync/indic-conformer-onnx-sherpa"

# TTS: Open Piper VITS models
TTS_LANGUAGE_REPOS = {
    "hi": "csukuangfj/vits-piper-hi_IN-pratham-medium",
    "kn": "csukuangfj/vits-piper-hi_IN-pratham-medium",
    "en-IN": "csukuangfj/vits-piper-en_US-amy-low",
}

VAD_REPO = "onnx-community/silero-vad"
SUPPORTED_LANGS = ["hi", "gu", "mr", "kn", "ta", "te", "ml", "or", "bn", "en-IN"]


def download_repo(repo_id: str, target_dir: str, allow_patterns=None):
    os.makedirs(target_dir, exist_ok=True)
    print(f"Downloading {repo_id} -> {target_dir}")
    token = os.environ.get("HF_TOKEN", None)
    try:
        snapshot_download(
            repo_id=repo_id,
            local_dir=target_dir,
            allow_patterns=allow_patterns,
            token=token,
            max_workers=4,
            resume_download=True,
        )
        print(f"  done: {repo_id}")
    except Exception as e:
        print(f"  FAILED: {repo_id}: {e}", file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lang", required=True,
                        help=f"Comma-separated language codes to fetch, from: {','.join(SUPPORTED_LANGS)}")
    parser.add_argument("--output", default="assets/models_raw",
                        help="Output directory for raw model files")
    parser.add_argument("--skip-vad", action="store_true")
    args = parser.parse_args()

    langs = [l.strip() for l in args.lang.split(",")]
    for l in langs:
        if l not in SUPPORTED_LANGS:
            print(f"Unsupported language code: {l}. Supported: {SUPPORTED_LANGS}", file=sys.stderr)
            sys.exit(1)

    if not args.skip_vad:
        # VAD only needs the onnx and config
        download_repo(
            VAD_REPO, 
            os.path.join(args.output, "vad"), 
            allow_patterns=["*.onnx", "*.json"]
        )

    for lang in langs:
        # STT only needs the onnx model and token definitions
        download_repo(
            STT_REPO,
            os.path.join(args.output, "stt", lang),
            allow_patterns=["*.onnx", "*.tokens", "*.txt", "*.json"]
        )
        
        # TTS only needs the onnx weights and config (skips 350+ unnecessary dict files)
        tts_repo = TTS_LANGUAGE_REPOS.get(lang, "csukuangfj/vits-piper-hi_IN-pratham-medium")
        download_repo(
            tts_repo,
            os.path.join(args.output, "tts", lang),
            allow_patterns=["*.onnx", "*.onnx.json", "*.txt", "*.json"]
        )

    print("\nNext steps:")
    print("  1. Run scripts/quantize_models.py to produce INT8 ONNX files.")


if __name__ == "__main__":
    main()