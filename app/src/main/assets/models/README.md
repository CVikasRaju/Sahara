# iTantra Model Assets Layout

This directory should contain the ONNX model files required by the Sherpa-ONNX
engines. The app degrades gracefully if files are missing (logs a warning, returns
no-op results), but real inference requires these files.

## Required Structure

```
app/src/main/assets/models/
├── vad/
│   └── silero_vad.onnx              ← Silero VAD v4/v5 model
├── stt/
│   ├── hi/                           ← Hindi STT
│   │   ├── encoder.int8.onnx
│   │   ├── decoder.int8.onnx
│   │   └── tokens.txt
│   ├── kn/                           ← Kannada STT
│   │   ├── encoder.int8.onnx
│   │   ├── decoder.int8.onnx
│   │   └── tokens.txt
│   └── <lang_code>/                  ← Other languages (gu, mr, ta, te, ml, or, bn, en-IN)
│       ├── encoder.int8.onnx
│       ├── decoder.int8.onnx
│       └── tokens.txt
└── tts/
    ├── hi/                           ← Hindi TTS
    │   ├── vits-hi.int8.onnx
    │   └── tokens.txt
    ├── kn/                           ← Kannada TTS
    │   ├── vits-kn.int8.onnx
    │   └── tokens.txt
    └── <lang_code>/                  ← Other languages
        ├── vits-<lang_code>.int8.onnx
        └── tokens.txt
```

## How to Obtain Models

1. Run `python scripts/fetch_models.py --lang hi,kn --output assets/models_raw`
2. Run `python scripts/quantize_models.py` to produce INT8 quantized ONNX files
3. Copy the quantized files into this directory matching the layout above
4. Verify tokens.txt matches the model (see docs/ML_PIPELINE.md §6)

## Notes

- The VAD model (`silero_vad.onnx`) is language-independent.
- STT models use the Whisper-style encoder/decoder architecture (IndicWhisper).
  If using IndicConformer (transducer architecture), update the model config in
  `SherpaSttEngine` to use `OfflineTransducerModelConfig` instead.
- TTS models use the VITS architecture (AI4Bharat Indic-TTS).
- All models should be INT8 quantized for mobile deployment (see docs/ML_PIPELINE.md §3).
