# Memoria de SubLater

- 2026-11-07: Releases GitHub: v1.0.22 = 1.1.0, v1.0.23 = 2.1.1, v1.0.24 = 2.2.0 (la más rápida); las etiquetas v1.0.x están en el remoto.
- 2026-11-07: Audio nativo en `ExoPlayerManager.kt`: WAV 16 kHz mono, 4 subidas Groq en paralelo y 4 hilos de FFmpeg; ffmpeg-kit no está como dependencia, así que usa Media3 + `FfmpegAudioDecoder`. La traducción vive en `index.html`.
- 2026-11-07: Optimización aplicada: chunks de audio 600 → 300 s y traducción por lotes en 4 hilos paralelos.
- 2026-11-07: APK compilado en `app/build/outputs/apk/debug/app-debug.apk`; el usuario pidió publicar el release como v1.0.24+.
- 2026-11-19: Seguridad y calidad: eliminado `scripts/publish_nuviolater.py` con token expuesto, `allowBackup=false`, reglas ProGuard para Media3 FFmpeg, audio focus + selector de pista de audio + borrado inmediato de chunks WAV + manejo de HTTP 429/errores de traducción; en `index.html`: fuente minimalista `Outfit` para el título `SubLater`, rediseño del chat IA con `gemini-4-hydrogen`, panel interactivo de subtítulos y fixes (0% volumen/opacidad, doble tap, horas en `formatTime`, contexto completo para Gemini).
- 2026-11-19: Renombrado visible `SubLater` → `SubLyter` (manteniendo `com.fromsub.app` y claves internas `sublater_*`), `versionCode = 68`, `versionName = "1.0.68"`; eliminadas etiquetas/versiones `2.x` (`v2.4.0-33` y CI), cambios commiteados y subidos a `main`, APK compilado en `app/build/outputs/apk/debug/app-debug.apk` y publicado release GitHub `v1.0.68` (`SubLyter-1.0.68.apk`).
- 2026-11-19: Версия 1.0.66 собрана из нового кода (SubLyter/Outfit/Gemini 4), история `main` сведена к двум коммитам (1.0.52 и 1.0.66); релизы, теги и APK v1.0.67/v1.0.68 удалены полностью.
- 2026-11-19: APK 1.0.66 (versionCode 66, `com.fromsub.app`) собран и залит в релиз `v1.0.66` вместо старого; тег `v1.0.66` перевешен на `719f3ed`.
