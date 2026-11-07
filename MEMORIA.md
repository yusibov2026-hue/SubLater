# Memoria de SubLater

- 2026-11-07: Releases GitHub: v1.0.22 = 1.1.0, v1.0.23 = 2.1.1, v1.0.24 = 2.2.0 (la más rápida); las etiquetas v1.0.x están en el remoto.
- 2026-11-07: Audio nativo en `ExoPlayerManager.kt`: WAV 16 kHz mono, 4 subidas Groq en paralelo y 4 hilos de FFmpeg; ffmpeg-kit no está como dependencia, así que usa Media3 + `FfmpegAudioDecoder`. La traducción vive en `index.html`.
- 2026-11-07: Optimización aplicada: chunks de audio 600 → 300 s y traducción por lotes en 4 hilos paralelos.
- 2026-11-07: APK compilado en `app/build/outputs/apk/debug/app-debug.apk`; el usuario pidió publicar el release como v1.0.24+.
