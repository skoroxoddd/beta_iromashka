# Iromashka Android v1 — Session Log

**Дата:** 2026-04-09
**Разработчик:** Claude Opus 4.6
**Цель:** Написать Android-клиент с нуля, упор на безопасность (Signal-level)

---

## Сессия 2026-04-09 — Создание проекта с нуля

### Анализ серверной документации v0.10.0
- Сервер: Rust + Axum + PostgreSQL, кластер 7-9 нод, hash ring, gRPC TLS
- API: JWT HS256 (1h access + 30d refresh), Argon2id PIN, E2E P-256+AES-256-GCM
- WebSocket: обфускация v1/v2/v3 (v3 = ChaCha20 с session key)
- HTTP Long-Polling fallback: POST /api/poll, POST /api/send
- MediaMeta: чанки для фото/аудио/видео
- device_id: preparation для per-device pubkey

### Созданные файлы (24 файла)

| Файл | Описание |
|------|----------|
| `build.gradle.kts` | Root build config |
| `settings.gradle.kts` | Project settings |
| `app/build.gradle.kts` | App build config (Compose, Room, Retrofit, OkHttp) |
| `app/src/main/AndroidManifest.xml` | Manifest + permissions + services |
| `app/src/main/res/values/strings.xml` | App name |
| `app/src/main/res/values/themes.xml` | Theme.Iromashka |
| `app/src/main/res/xml/network_security_config.xml` | Cleartext disabled |
| `crypto/CryptoManager.kt` | E2E: P-256 + AES-256-GCM + PBKDF2 + HKDF |
| `model/Models.kt` | Все REST и WS модели + device_id |
| `model/TypingEvent.kt` | Typing event model |
| `network/ApiService.kt` | Retrofit API + AuthInterceptor |
| `network/WsClient.kt` | WebSocket + обфускация v1/v2/v3 |
| `storage/Prefs.kt` | EncryptedSharedPreferences + PIN lockout (5 попыток) |
| `storage/AppDatabase.kt` | Room Database (iromashka.db) |
| `storage/MessageEntity.kt` | Entities: messages, contacts, group_messages |
| `storage/MessageDao.kt` | DAO: MessageDao, ContactDao, GroupMessageDao |
| `viewmodel/AuthViewModel.kt` | Регистрация, логин, refresh, changePin |
| `viewmodel/ChatViewModel.kt` | Сообщения, группы, статусы, контакты |
| `ui/theme/Theme.kt` | RosePalette + QipPalette |
| `ui/screens/LoginScreen.kt` | Вход (UIN + PIN) |
| `ui/screens/RegisterScreen.kt` | Регистрация (телефон + PIN) |
| `ui/screens/PinUnlockScreen.kt` | Разблокировка (5 попыток → удаление) |
| `ui/screens/ContactListScreen.kt` | Контакты + добавление по UIN |
| `ui/screens/ChatScreen.kt` | Личный чат |
| `ui/screens/GroupChatScreen.kt` | Групповой чат |
| `ui/screens/PhoneLookupScreen.kt` | Поиск по телефонной книге |
| `ui/screens/SettingsScreen.kt` | Настройки, смена PIN, темы |
| `service/IromashkaForegroundService.kt` | Фоновый сервис + push |
| `service/BootReceiver.kt` | Автозапуск после перезагрузки |
| `MainActivity.kt` | Entry point + NavHost + FLAG_SECURE |

### Реализованные функции безопасности
1. **FLAG_SECURE** — запрет скриншотов
2. **EncryptedSharedPreferences** — токены и ключи зашифрованы
3. **PIN lockout: 5 попыток** → прогрессивная блокировка
4. **ChaCha20 v3** — поддержка всех 3 версий обфускации
5. **device_id** — UUID для per-device pubkey
6. **Network Security Config** — cleartext запрещён
7. **E2E шифрование** — P-256 + AES-256-GCM + Forward Secrecy

### Следующие шаги
- Добавить звуки в res/raw/
- Собрать APK
- Протестировать
