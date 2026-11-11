# Memoria de SubLater

- 2026-11-07: El usuario prefiere la base 1.0.24 (v2.2.0, extracción JS) sin el camino nativo de 2.4.0.
- 2026-11-07: Rama `fast-1.0.24` desde el tag v1.0.24: 12 kHz, chunks 300→180 s, overlap 6→3 s, 6 subidas Groq y 6 traducciones en paralelo; versionCode 27.
- 2026-11-07: APK compilado en `app/build/outputs/apk/debug/app-debug.apk`.
- 2026-11-07: Segundo campo de clave Groq (`apiKey2`, alias seguro `groq_api_key_2`) y reparto round-robin entre claves en cada petición; versionCode 28, versionName 1.0.25.
- 2026-11-10: Turbo-ускорение v3: 8 kHz (было 12), чанки 60 с (было 180), overlap 1 с (было 3), 10 потоков (было 6), всегда сжимать при включённой опции; versionCode 29, versionName 1.0.26.
- 2026-11-10: Возврат 16 кГц, потоковый пайплайн перевода чанков на лету (Groq chat параллельно с Whisper), поддержка N ключей Groq, локальная история IndexedDB, кнопка «Поделиться» (Android FileProvider/ACTION_SEND); versionCode 30, versionName 1.0.27.
- 2026-11-10: Живые субтитры в плеере: после первого готового чанка плеер открывается и cues дополняются по мере распознавания/перевода следующих чанков (index.html, flushLiveCues); заодно исправлена область видимости totalChunks/pipelineTranslatedCuesMap; versionCode 31, versionName 1.0.28. Полное аудио всё ещё декодируется заранее.
- 2026-11-10: APK 1.0.28 собран: `app/build/outputs/apk/debug/app-debug.apk` (BUILD SUCCESSFUL).
- 2026-11-10: Гибридная отправка: первый чанк отдельно (быстрый старт живых субтитров), затем остальные параллельно; кнопка «Поделиться» перенесена в меню «Скачать»; versionCode 32, versionName 1.0.29.
- 2026-11-10: APK 1.0.29 собран: `app/build/outputs/apk/debug/app-debug.apk` (BUILD SUCCESSFUL).
