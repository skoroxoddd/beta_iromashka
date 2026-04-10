use x25519_dalek::{StaticSecret, PublicKey};
use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
use aes_gcm::aead::Aead;
use hkdf::Hkdf;
use sha2::Sha256;
use base64::{Engine, engine::general_purpose::STANDARD};
use rand::RngCore;

pub struct CryptoManager {
    pub secret_key: StaticSecret,
    public_key: PublicKey,
}

impl CryptoManager {
    pub fn new() -> Self {
        let mut rng = rand::thread_rng();
        let secret_key = StaticSecret::random_from_rng(&mut rng);
        let public_key = PublicKey::from(&secret_key);
        Self { secret_key, public_key }
    }

    pub fn public_key_base64(&self) -> String {
        STANDARD.encode(self.public_key.as_bytes())
    }

    pub fn secret_key_base64(&self) -> String {
        STANDARD.encode(self.secret_key.to_bytes())
    }

    pub fn from_secret_key_base64(b64: &str) -> Result<Self, String> {
        let bytes = STANDARD.decode(b64).map_err(|e| e.to_string())?;
        let bytes: [u8; 32] = bytes.try_into().map_err(|_| "Invalid key length")?;
        let secret_key = StaticSecret::from(bytes);
        let public_key = PublicKey::from(&secret_key);
        Ok(Self { secret_key, public_key })
    }

    /// X25519 + HKDF -> AES-256-GCM encrypt
    pub fn encrypt_message(plaintext: &str, recipient_pubkey_b64: &str) -> Result<String, String> {
        let ephemeral_secret = StaticSecret::random_from_rng(&mut rand::thread_rng());
        let ephemeral_public = PublicKey::from(&ephemeral_secret);

        let recipient_bytes = STANDARD.decode(recipient_pubkey_b64).map_err(|e| e.to_string())?;
        let recipient_pubkey = PublicKey::from(<[u8; 32]>::try_from(recipient_bytes.as_slice()).map_err(|_| "Bad pubkey")?);

        let shared_secret = ephemeral_secret.diffie_hellman(&recipient_pubkey);

        let hk = Hkdf::<Sha256>::new(None, shared_secret.as_bytes());
        let mut aes_key = [0u8; 32];
        hk.expand(b"iromashka-msg", &mut aes_key).map_err(|e| e.to_string())?;

        let cipher = Aes256Gcm::new(&aes_key.into());
        let mut nonce_bytes = [0u8; 12];
        rand::thread_rng().fill_bytes(&mut nonce_bytes);
        let nonce = Nonce::from_slice(&nonce_bytes);

        let ct = cipher.encrypt(nonce, plaintext.as_bytes()).map_err(|e| e.to_string())?;

        let result = serde_json::json!({
            "v": 1,
            "epk": STANDARD.encode(ephemeral_public.as_bytes()),
            "iv": STANDARD.encode(&nonce_bytes),
            "ct": STANDARD.encode(&ct)
        });
        Ok(result.to_string())
    }

    pub fn decrypt_message_with_key(secret_key: &StaticSecret, ciphertext_json: &str) -> Result<String, String> {
        let data: serde_json::Value = serde_json::from_str(ciphertext_json).map_err(|e| e.to_string())?;

        let epk_b64 = data["epk"].as_str().ok_or("missing epk")?;
        let iv_b64 = data["iv"].as_str().ok_or("missing iv")?;
        let ct_b64 = data["ct"].as_str().ok_or("missing ct")?;

        let epk_bytes = STANDARD.decode(epk_b64).map_err(|e| e.to_string())?;
        let ephemeral_pubkey = PublicKey::from(<[u8; 32]>::try_from(epk_bytes.as_slice()).map_err(|_| "Bad epk")?);

        let shared_secret = secret_key.diffie_hellman(&ephemeral_pubkey);

        let hk = Hkdf::<Sha256>::new(None, shared_secret.as_bytes());
        let mut aes_key = [0u8; 32];
        hk.expand(b"iromashka-msg", &mut aes_key).map_err(|e| e.to_string())?;

        let cipher = Aes256Gcm::new(&aes_key.into());
        let nonce_bytes = STANDARD.decode(iv_b64).map_err(|e| e.to_string())?;
        let ct_bytes = STANDARD.decode(ct_b64).map_err(|e| e.to_string())?;
        let nonce = Nonce::from_slice(&nonce_bytes);

        let pt = cipher.decrypt(nonce, ct_bytes.as_slice()).map_err(|e| e.to_string())?;
        String::from_utf8(pt).map_err(|e| e.to_string())
    }
}
