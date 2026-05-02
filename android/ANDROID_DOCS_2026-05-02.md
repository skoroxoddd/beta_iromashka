# iRomashka Android — Snapshot 2026-05-02

> v1.7.20 (versionCode=53) | Repo: github.com/skoroxoddd/beta_iromashka
> Local: `/tmp/beta_iromashka_study/android/`

---

## 1. Архитектура

```
MainActivity (Compose)
  ├── AuthViewModel       — register/login/refresh/unlock_token
  ├── ChatViewModel       — messages, sync, identity reset, contacts
  ├── ApiService          — Retrofit + OkHttp (CertificatePinner, H1)
  ├── WsClient            — coroutine WS handler, multi-fallback v4/v3/plain
  ├── CryptoManager       — ECDH P-256 + HKDF + AES-GCM (M1)
  ├── Bip39               — recovery wrap (Argon2id ARG1, PBKDF2 fallback)
  └── Prefs               — EncryptedSharedPreferences (Keystore-backed)

Local DB: Room (messages, contacts, draft) — 90d-aligned с server retention
```

**Стек**: Kotlin + Jetpack Compose + Coroutines + Retrofit + OkHttp + Room + Argon2kt JNI.
minSdk=24, targetSdk=34.

---

## 2. CryptoManager (`crypto/CryptoManager.kt`)

### Identity
```kotlin
generateKeyPair() -> KeyPair  // P-256 (secp256r1)
exportPublicKey(pub) -> b64 SPKI
importPublicKey(b64) -> PublicKey
```

### Wrap privkey (C3)
```
wrapPrivateKey(priv, pin) -> b64( "ARG1"(4) || salt(16) || iv(12) || AES-GCM_ct )
  Argon2id(pin, salt, m=19MiB, t=2, p=1, hashLen=32) → AES key
unwrapPrivateKey(wrapped, pin) -> PrivateKey
  Reader-tolerant: ARG1 / legacy compact PBKDF2-250k / PWA JSON {ct,iv,salt}
```
Зависимость: `com.lambdapioneer.argon2kt:argon2kt:1.6.0` (~600KB JNI на 3 ABI).

### Сообщение (M1 v=3)
```
encryptMessage(pt, recipPub) -> JSON {"v":3, epk, iv, ct}
  eph = generateKeyPair()
  shared = ECDH(eph.priv, recipPub)
  aesKey = HKDF-SHA256(shared, salt=zeros(32), info="irm-msg-v3"||epk_spki, L=32)
  ct = AES-256-GCM(aesKey, iv=random12, pt)

decryptMessage(json, myPriv) -> String?
  v=3 → ecdhAesKeyHkdf(myPriv, epk, epk_spki)
  v<=2 → ecdhAesKey(myPriv, epk)  // legacy raw shared
```

`hkdfSha256` — ручной через `Mac.HmacSHA256` (нет нативного HKDF в JCA).

---

## 3. Сетевой слой (`network/ApiService.kt`)

### Cert-pinning (H1)
8 SPKI пинов: leaf E8 + LE intermediates E5-E9 + ISRG X1 (kill-switch до 2030) + ISRG X2 (до 2035).
Менять за месяц до миграции LE на E10+.

### Endpoints (Retrofit)
```kotlin
@POST("auth/login")           login(LoginRequest) -> LoginResponse
@POST("auth/refresh")         refresh(RefreshRequest) -> RefreshResponse
@POST("auth/change_pin")      changePin(...)
@POST("identity/reset/challenge")  identityResetChallenge() -> {nonce, expires_at}
@POST("identity/reset")       identityReset(IdentityResetRequest{uin,pin,nonce})
@POST("update-pubkey")        updatePubkey(...)
@POST("save-key")             saveUserKey(...)  // sender_ciphertext
@POST("messages/sync")        saveSyncedMessage(...)
@POST("recovery/init|lookup|save")
@POST("unlock/issue|verify|revoke")
@POST("contacts/discover")
@GET  ("user/{uin}/devices")  списки pubkey'ов
```

### network_security_config.xml (H3)
```xml
<base-config cleartextTrafficPermitted="false">
  <trust-anchors><certificates src="system"/></trust-anchors>
</base-config>
<debug-overrides>... user CA ...</debug-overrides>
```
**Никаких user-CA в release.**

---

## 4. Auth flow

### Register
1. PIN вводится → `wrapPrivateKey(newKp.priv, pin)` → ARG1 blob
2. `register({phone, pin, pubkey, encrypted_key=blob})` → JWT+refresh+UIN
3. PIN-hash на сервере = bcrypt
4. Опционально recovery: `Bip39.generateMnemonic` → wrap отдельным mnemonic-key + upload

### Login (UIN+PIN)
1. `login({uin, pin})` → `{token, refresh_token, encrypted_key?}`
2. Если `encrypted_key=null` → **auto-reset path** (см. ChatViewModel:277):
   - identity/reset/challenge → identity/reset с nonce → genKp → wrap → updatePubkey
3. Иначе `unwrapPrivateKey(encrypted_key, pin)` → privkey в RAM
4. `MainActivity.maybeOfferBiometricEnroll` → `wrapWithBiometric` unlock_token
5. Prefs хранят: token, refresh_token, wrappedPriv (ARG1), pubkey, biometric-wrapped unlock_token

### Fast-resume (G2 unlock_token, биометрия)
1. App-open → biometric prompt → unlock unlock_token из Keystore
2. `/unlock/verify` с unlock_token → новый JWT+refresh
3. WS connect

### Identity reset (H5, manual)
```
ChatViewModel.resetIdentity(pin):
  ch = api.identityResetChallenge(token)
  api.identityReset(token, IdentityResetRequest{uin, pin, ch.nonce})
  → wipe local Prefs (wrapped_priv, pubkey)
  → next login bootstraps fresh keypair
```

---

## 5. ChatViewModel — ключевая логика

- `myPrivKey: PrivateKey?` — в RAM после unwrap, **не сохраняется обратно**
- `pubkeyCache: Map<UIN, PublicKey>` — пересчитывается при `pubkey_changed` WS event
- `outgoing(text, recipUin)`:
  1. `recipPub = pubkeyCache[recipUin]` (или fetch + cache)
  2. `cipherForRecip = encryptMessage(text, recipPub)` (v=3)
  3. `cipherForSelf  = encryptMessage(text, myPub)`  (для других своих сессий)
  4. WS send `msg` payload `{recipient_uin, recipient_ciphertext, sender_ciphertext, client_msg_id}`
- `incoming(payload)`:
  1. dedupe по `client_msg_id` (Room unique)
  2. `decryptMessage(payload.ciphertext, myPrivKey)` — диспатч v=2/v=3
  3. insert в Room → notify Compose state

---

## 6. Secure UI (H4)

`ui/SecureScreen.kt`:
```kotlin
@Composable fun SecureScreen() { /* FLAG_SECURE через DisposableEffect */ }
fun copySensitive(ctx, label, text, autoClearMs=60_000) { /* EXTRA_IS_SENSITIVE + auto-clear */ }
```
Применён на: PinUnlock, UinReveal, GenerateMnemonic, RestoreFromMnemonic.

---

## 7. ProGuard (H2)

`proguard-rules.pro`:
- `-assumenosideeffects` для `Log.v/d/i/w` (Log.e оставлен для крашей)
- `-keep class com.lambdapioneer.argon2kt.**`
- `-dontwarn` JNI

---

## 8. Структура файлов (актуально 02.05)

```
android/app/src/main/java/com/iromashka/
  MainActivity.kt
  crypto/
    CryptoManager.kt   — M1 HKDF + C3 ARG1
    Bip39.kt           — recovery (Argon2id reader, PBKDF2 writer)
  network/
    ApiService.kt      — Retrofit + CertificatePinner (H1)
    WsClient.kt        — multi-fallback transport
    ChaCha20.kt        — obfuscation v3/v4
  viewmodel/
    AuthViewModel.kt   — fetchUnlockToken (без записи в Prefs)
    ChatViewModel.kt   — messaging, identity reset с nonce
  ui/
    SecureScreen.kt    — FLAG_SECURE + copySensitive (H4)
    screens/{PinUnlock,UinReveal,GenerateMnemonic,RestoreFromMnemonic}Screen.kt
  data/Prefs.kt        — EncryptedSharedPreferences

android/app/
  build.gradle.kts     — versionCode=53, versionName=1.7.20
  proguard-rules.pro   — H2 strip Log + Argon2kt keep
  src/main/res/xml/
    network_security_config.xml — H3
```

---

## 9. Сборка

CI: push в `main` → GitHub Actions → APK артефакт `iromashka-v1.7.20.apk`.

Локально:
```bash
cd /tmp/beta_iromashka_study/android
./gradlew assembleRelease  # требует keystore.jks + signing.properties
```

Установка поверх (НЕ uninstall): `adb install -r iromashka-v1.7.20.apk`.

---

## 10. История версий (security sprint 02.05)

- **v1.7.18**: C2/C3/H1-H4 + C1 server
- **v1.7.19**: H5 minimal (nonce-gated identity reset)
- **v1.7.20**: M1 (HKDF-SHA256 over ECDH, v=3 messages)

**Reader-compatibility**: v1.7.20 читает все v=2 и v=3, пишет v=3.
v1.7.18- читает только v=2 — не сможет расшифровать сообщения от v1.7.20.
