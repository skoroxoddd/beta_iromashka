# АйРомашка — Документация Android-приложения

> Версия: 1.0.0 | Дата: 2026-04-05 | Статус: Готово к сборке

---

## Кратко

Нативный Android-мессенджер на Kotlin + Jetpack Compose с E2E-шифрованием (Signal-модель) и ретро-интерфейсом в стиле QIP 2005 / WinXP.

---

## Технический стек

| Компонент | Технология |
|-----------|-----------|
| UI | Jetpack Compose + Material3 |
| Навигация | Navigation Compose 2.7.7 |
| Сетевой стек | OkHttp 4.12 (WS + HTTP), Retrofit 2.9 |
| БД | Room 2.6.1 (SQLite) |
| Безопасность | EncryptedSharedPreferences (Android Keystore) |
| Крипто | Java JCE: P-256 ECDH, AES-256-GCM, PBKDF2, HKDF-SHA256 |
| Мин. Android | 33 (Android 13) |
| Целевой Android | 34 (Android 14) |
| Мин. Java | 17 |

---

## Архитектура

### Flow авторизации
```
Launch → Logged in? → No → LoginScreen
                             ↓
                       RegisterScreen (создаёт ключевую пару, сохраняет в Keystore)
                             ↓
                       PinUnlockScreen (разворачивает приватный ключ)
                             ↓
                       ContactListScreen (запускает WS)
```

1. **LoginScreen** → UIN + PIN → POST /api/login → JWT
2. **PinUnlockScreen** → PIN → `CryptoManager.unwrapPrivateKey()` → приватный ключ в RAM
3. **ContactListScreen** → `connectWs()` → WebSocket авторизация через `{uin, token}`

### WebSocket протокол

**Подключение:**
```
URL: wss://iromashka.ru/chat
Protocol: Sec-WebSocket-Protocol: binary, text
Obfuscation: [IR 2B][ver 1B][len 4B LE][payload][padding 0-64B]
```

**Пакеты:**
```json
// Auth (первый после onOpen)
{"uin": 123456789, "token": "jwt..."}

// Message (отправка от сервера)
{"sender_uin": 111, "receiver_uin": 222, "ciphertext": "{...}", "timestamp": 1234567890}

// Typing
{"type": "Typing", "data": {"sender_uin": 111, "receiver_uin": 222, "is_typing": true}}
```

### Модель E2E-шифрования

Совместима с JavaScript WebCrypto API (браузерный клиент).

**Регистрация (клиент):**
1. Генерация P-256 ECDH keypair через `KeyPairGenerator("EC")`
2. Публичный ключ → SPKI Base64 → сервер (хранится в БД)
3. Приватный ключ → PBKDF2(PIN, 250k итераций) → AES-256-GCM → EncryptedSharedPreferences

**Отправка сообщения:**
1. `api.getPubKey(recipientUin)` — получить публичный ключ получателя
2. Эфемерная генерация ECDH keypair
3. `ECDH(ephemeralPriv, recipientPub)` → HKDF-SHA256 → AES-256-GCM ключ
4. Шифрование текста → JSON `{v: 1, epk: "...", iv: "...", ct: "..."}`
5. Эфемерный ключ **уничтожается** (Forward Secrecy)

**Получение сообщения:**
1. JSON → извлечь `epk` (ephemeral публичный ключ отправителя)
2. `ECDH(myPrivKey, ephPub)` → HKDF → расшифровка AES-256-GCM
3. Сохранение в Room DB (расшифрованный текст)

### Фоновая работа (без Firebase)

**IromashkaForegroundService:**
- Запускается `START_STICKY` после входа юзера
- Держит собственное WebSocket-подключение
- При входящем сообщении — показывает системное уведомление
- Обфускация транспорта та же, что у WsClient
- Авто-реконнект с backoff (1с → 2с → 4с → ... → 30с макс)

---

## REST API (сервер)

| Метод | Эндпоинт | Описание |
|-------|----------|----------|
| POST | /api/register | Регистрация (ник, PIN, public_key) → UIN + JWT |
| POST | /api/login | Вход (UIN, PIN) → JWT + refresh_token |
| POST | /api/refresh | Обновление токена (Bearer + refresh_token) → новые токены |
| POST | /api/change-pin | Смена PIN (Bearer) → новый JWT |
| GET | /api/pubkey/{uin} | Получить публичный ключ пользователя |
| PUT | /api/fcm-token | Привязать push-токен (отключён, код есть) |
| POST | /api/create-group | Создать группу (Bearer) |
| GET | /api/groups | Список групп юзера |
| GET | /api/group/{id}/members | Участники группы |
| POST | /api/group/{id}/members | Добавить участника |
| DELETE | /api/account | Удалить аккаунт (Bearer) |

---

## Хранение данных

### EncryptedSharedPreferences (Keystore)
| Ключ | Тип | Описание |
|------|-----|----------|
| uin | Long | Номер пользователя |
| nickname | String | Имя |
| token | String | JWT access token |
| refresh_token | String | JWT refresh token |
| token_ts | Long | Timestamp получения токена |
| wrapped_priv | String | Зашифрованный приватный ключ |
| pub_key | String | Публичный ключ (Base64 SPKI) |

### Room Database (icq20.db)
| Таблица | Поля |
|---------|------|
| messages | id, chatUin, senderUin, receiverUin, text, timestamp, isOutgoing, isE2E |
| contacts | uin, nickname, addedAt |
| group_messages | id, groupId, senderUin, senderNickname, text, timestamp, ciphertext |

---

## Настройки сборки

### build.gradle.kts (app)
```kotlin
namespace = "com.iromashka"
applicationId = "com.iromashka"
minSdk = 33
targetSdk = 34
versionCode = 1
versionName = "1.0.0"
```

### Звуки
| Файл | Когда играет |
|------|-------------|
| `iromashka_message.wav` | Входящее сообщение |
| `iromashka_outgoing.wav` | Исходящее сообщение |

---

## PIN Lockout (защита от перебора)

| Попыток подряд | Блокировка |
|----------------|------------|
| 1-4 | Нет |
| 5-9 | 30 секунд |
| 10-14 | 1 минута |
| 15-19 | 5 минут |
| 20-24 | 15 минут |
| 25-29 | 30 минут |
| 30-34 | 1 час |
| 35-39 | 4 часа |
| 40-49 | 24 часа |
| 50+ | Приватные ключи удалены из EncryptedSharedPreferences |

---

## Известные ограничения

### Групповые чаты
- UI работает (создание, участники, отправка, отображение истории)
- **E2E broken:** `sendGroupMessage()` шифрует сообщение публичным ключом отправителя. Каждый участник группы должен получать расшифрованную копию, но сервер не делает relay через всех участников.
- **Исправление:** Нужен протокол Sender Keys (как у Signal) или серверный relay с повторным шифрованием.

### Голосовые сообщения
- Нет в приложении (ни UI, ни permission RECORD_AUDIO)
- На сервере нет таблицы/обработчика для voice

### Обновление приложения
- Нет in-app update механизма
- Для Play Store: нужно настроить Android App Bundle + подписанный release APK

---

## Как собрать

### Debug
```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/iromashka-v1.0.0.apk
```

### Release (нужен keystore)
Создать `keystore.jks`:
```bash
keytool -genkey -v -keystore iromashka-release.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias iromashka
```

Добавить в `app/build.gradle.kts` до `buildTypes`:
```kotlin
signingConfigs {
    create("release") {
        storeFile = file("../../iromashka-release.jks")
        storePassword = "YOUR_PASSWORD"
        keyAlias = "iromashka"
        keyPassword = "YOUR_PASSWORD"
    }
}
buildTypes.release.signingConfig = signingConfigs.getByName("release")
```

```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/iromashka-v1.0.0.apk
```
