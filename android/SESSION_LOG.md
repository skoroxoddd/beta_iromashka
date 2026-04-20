# Android-приложение «АйРомашка» — Лог сессии

## Дата: 2026-04-05
## Разработчик: Claude (AI-ассистент)
## Сервер-хост: 91.236.186.70 (`/home/iromashka_2/`)

---

## Выполненные работы

### 1. Брендинг: ICQ → АйРомашка
- Все звуки переименованы: `icq_*.wav` + `qip_*.wav` → `iromashka_*.wav`
- Сервис: `IcqFcmService` → `IromashkaForegroundService` (класс + манифест)
- Тема: `enum ThemeMode.ICQ` → `ThemeMode.Iromashka`
- Project name: `icq20` → `iromashka`
- APK output: `app-debug.apk` → `iromashka-v$versionName.apk`
- **Оставлено как `icq20`:** HKDF_INFO (`icq20-msg`), SharedPreferences ключи (`icq20_secure`), имя БД (`icq20.db`) — смена сломает существующие сессии пользователей

### 2. JWT Refresh tokens (критический фикс)
**Проблема:** access-токен живёт 1 час. Без рефреша юзер теряет соединение.
**Решение:**
- `Prefs.kt` — добавлены: `getRefreshToken()`, `updateRefreshToken()`, `getTokenTimestamp()`, `updateTokenTimestamp()`
- `Prefs.saveSession()` — теперь принимает `refreshToken` параметр
- `AuthViewModel.register()` — сохраняет `refresh_token`
- `AuthViewModel.login()` — сохраняет `refresh_token` + timestamp
- `ChatViewModel.refreshToken()` — вызывает POST /api/refresh, ребилдит WS
- `ChatViewModel.shouldRefreshToken()` — проверяет возраст токена (>50 мин)
- `MainActivity.kt` — при входе в контакты проверяет и рефрешит
- `ApiService.kt` — добавлен POST /api/refresh endpoint

### 3. Смена PIN в UI
**Проблема:** кнопка "Сменить PIN" была заглушка (onClick пустой).
**Решение:**
- `ContactListScreen.kt` — добавлена переменная `showPinChange`
- Меню → "Сменить PIN" теперь открывает `PinChangeDialog`
- `PinChangeDialog` — Composable с полями: текущий PIN, новый PIN, подтверждение
- Вызывает `AuthViewModel.changePin()` + валидация

### 4. Proguard правила (критический фикс для release)
**Проблема:** `proguard-rules.pro` указывал `ru.iromashka`, но пакет `com.iromashka`. Release-сборка крашится при obfuscation.
**Решение:** Переписаны все `-keep` правила на `com.iromashka.**`

### 5. Foreground Service (без Firebase = WS в фоне)
**Проблема:** `IromashkaForegroundService` был заглушкой — только показывал notification, WS не держал.
**Решение:**
- Полностью переписано: теперь Service сам держит WebSocket-подключение
- При входящем сообщении — показывает notification с текстом сообщения
- Авто-реконнект при разрыве (exponential backoff до 30 сек)
- `START_STICKY` — система перезапустит сервис при убийстве
- BootReceiver для автозапуска при ребуте (TODO)

### 6. Удаление FCM
- Убран `com.google.gms.google-services` плагин из `build.gradle.kts`
- Убраны зависимости Firebase (их и так не было, плагин был мертвый)

### 7. Дубликат ChatMessage
**Проблема:** `ChatMessage` определён дважды — в `Models.kt` и `ChatViewModel.kt`.
**Решение:** Удалён дубликат из `Models.kt`. Используется `com.iromashka.viewmodel.ChatMessage`.

---

## Архитектура приложения

### Пакет: `com.iromashka`
```
ui/screens/
  LoginScreen        — Вход по UIN + PIN
  RegisterScreen     — Регистрация (ник + PIN + телефон)
  PinUnlockScreen    — Разблокировка ключа
  ContactListScreen  — Контакты + группы + темы + смена PIN
  ChatScreen         — Чат 1-to-1 с E2E
  GroupChatScreen    — Групповой чат
ui/theme/
  Theme              — 2 темы: Iromashka (ICQ-стиль) / QIP (тёмная)
crypto/
  CryptoManager      — P-256 ECDH + AES-256-GCM + PBKDF2 + HKDF
network/
  ApiService         — REST API (Retrofit) + WS_URL
  WsClient           — WebSocket c обфускацией + реконнект
viewmodel/
  AuthViewModel      — Регистрация / Вход / Смена PIN / Refresh tokens
  ChatViewModel      — WS + шифрование + Room DB + группы + typing + refresh
storage/
  Prefs              — EncryptedSharedPreferences (Keystore) + токены + PIN lockout
  AppDatabase        — Room (messages, contacts, group_messages)
  MessageEntity      — Room entity
  GroupMessageEntity — Room entity
  MessageDao         — DAOs для сообщений + контактов + групп
model/
  Models             — REST API и WS data classes
  TypingEvent        — Typing event
service/
  IromashkaForegroundService — WS в фоне + уведомления
MainActivity       — Navigation Compose + Auth flow
```

### Безопасность
- **E2E:** P-256 ECDH, AES-256-GCM, эфемерные ключи (Forward Secrecy)
- **PIN:** PBKDF2 250k итераций → AES-256-GCM обёртка приватного ключа
- **Хранение:** EncryptedSharedPreferences (Android Keystore AES256-GCM)
- **Transport:** wss://iromashka.ru + Transport Obfuscation
- **PIN lockout:** Экспоненциальная задержка после 5 неверных попыток (30с → 1м → 5м → ... → 24ч → вайп ключей)

### Сервер (72.56.11.228)
- Rust + Axum + tokio
- PostgreSQL 14
- JWT HS256 (1ч access + 30д refresh, ротация)
- WebSocket на wss://iromashka.ru/chat
- Rate limiting на login (5 попыток → 15м блок)
- Multi-node через gRPC
- FCM push (сервер готов, клиент выпилил → WS в фоне)

---

## Что НЕ сделано (на момент сборки)

### Критичное
- ❌ Групповое E2E шифрование — `sendGroupMessage` шифрует своим публичным ключом
- ❌ Нет iOS-приложения — проект `/home/iromashka-ios/` содержит только иконки
- ❌ Нет голосовых сообщений — нет UI/permission на сервере или в APK

### Желательное
- ⚠️ Нет BootReceiver для автозапуска Foreground Service
- ⚠️ Нет Crashlytics/Sentry
- ⚠️ Нет механизма обновления (in-app update)
- ⚠️ Нет signed release APK (нужен keystore)

---

## Сборка

### Debug
```bash
cd /home/iromashka_2          # или C:\123\iromashka-android на Windows
./gradlew assembleDebug       # или gradlew.bat на Windows
```
Output: `app/build/outputs/apk/debug/iromashka-v1.0.0.apk`

### Release
Нужен keystore. Добавить `signingConfigs` в `app/build.gradle.kts`, затем `assembleRelease`.

---

## Командная строка для скачивания проекта
```bash
scp -r root@91.236.186.70:/home/iromashka_2 ./iromashka-android
```
