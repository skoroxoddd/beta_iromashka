use p256::{
    ecdh::EphemeralSecret,
    PublicKey, SecretKey,
    pkcs8::{DecodePublicKey, EncodePublicKey, DecodePrivateKey, EncodePrivateKey},
};
use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
use aes_gcm::aead::Aead;
use base64::{Engine, engine::general_purpose::STANDARD};
use rand::rngs::OsRng;

/// P-256 ECDH + AES-256-GCM encryption compatible with Android/PWA
pub struct CryptoManager {
    secret_key: SecretKey,
    public_key: PublicKey,
}

impl CryptoManager {
    pub fn new() -> Self {
        let secret_key = SecretKey::random(&mut OsRng);
        let public_key = secret_key.public_key();
        Self { secret_key, public_key }
    }

    /// Export public key as base64 SPKI (X.509 SubjectPublicKeyInfo)
    pub fn public_key_base64(&self) -> String {
        let spki = self.public_key.to_public_key_der().expect("SPKI export");
        STANDARD.encode(spki.as_bytes())
    }

    /// Export secret key as base64 PKCS#8
    pub fn secret_key_base64(&self) -> String {
        let pkcs8 = self.secret_key.to_pkcs8_der().expect("PKCS8 export");
        STANDARD.encode(pkcs8.as_bytes())
    }

    pub fn from_secret_key_base64(b64: &str) -> Result<Self, String> {
        let bytes = STANDARD.decode(b64).map_err(|e| e.to_string())?;
        let secret_key = SecretKey::from_pkcs8_der(&bytes).map_err(|e| e.to_string())?;
        let public_key = secret_key.public_key();
        Ok(Self { secret_key, public_key })
    }

    /// Import recipient public key from base64 SPKI
    fn import_public_key(b64: &str) -> Result<PublicKey, String> {
        let bytes = STANDARD.decode(b64).map_err(|e| e.to_string())?;
        PublicKey::from_public_key_der(&bytes).map_err(|e| e.to_string())
    }

    /// Encrypt message for recipient using ephemeral ECDH + AES-256-GCM
    /// Output format: {"v":2,"epk":"...","iv":"...","ct":"..."} — matches Android/PWA
    pub fn encrypt_message(plaintext: &str, recipient_pubkey_b64: &str) -> Result<String, String> {
        let recipient_pub = Self::import_public_key(recipient_pubkey_b64)?;

        // Generate ephemeral keypair
        let ephemeral_secret = EphemeralSecret::random(&mut OsRng);
        let ephemeral_public = ephemeral_secret.public_key();

        // ECDH shared secret
        let shared_secret = ephemeral_secret.diffie_hellman(&recipient_pub);

        // Use raw shared secret as AES key (first 32 bytes) — matches Android CryptoManager
        let shared_bytes = shared_secret.raw_secret_bytes();
        let aes_key: [u8; 32] = shared_bytes[..32].try_into().map_err(|_| "Bad shared secret")?;

        // AES-256-GCM encrypt
        let cipher = Aes256Gcm::new(&aes_key.into());
        let mut nonce_bytes = [0u8; 12];
        rand::RngCore::fill_bytes(&mut OsRng, &mut nonce_bytes);
        let nonce = Nonce::from_slice(&nonce_bytes);
        let ct = cipher.encrypt(nonce, plaintext.as_bytes()).map_err(|e| e.to_string())?;

        // Export ephemeral public key as SPKI
        let epk_der = ephemeral_public.to_public_key_der().map_err(|e| e.to_string())?;

        let result = serde_json::json!({
            "v": 2,
            "epk": STANDARD.encode(epk_der.as_bytes()),
            "iv": STANDARD.encode(&nonce_bytes),
            "ct": STANDARD.encode(&ct)
        });
        Ok(result.to_string())
    }

    /// Decrypt message using own secret key
    pub fn decrypt_message_with_key(secret_key: &SecretKey, ciphertext_json: &str) -> Result<String, String> {
        let data: serde_json::Value = serde_json::from_str(ciphertext_json).map_err(|e| e.to_string())?;

        let epk_b64 = data["epk"].as_str().ok_or("missing epk")?;
        let iv_b64 = data["iv"].as_str().ok_or("missing iv")?;
        let ct_b64 = data["ct"].as_str().ok_or("missing ct")?;

        // Import ephemeral public key
        let epk_bytes = STANDARD.decode(epk_b64).map_err(|e| e.to_string())?;
        let ephemeral_pub = PublicKey::from_public_key_der(&epk_bytes).map_err(|e| e.to_string())?;

        // ECDH with our secret key
        let shared_secret = p256::ecdh::diffie_hellman(
            secret_key.to_nonzero_scalar(),
            ephemeral_pub.as_affine()
        );

        // Use raw shared secret as AES key
        let shared_bytes = shared_secret.raw_secret_bytes();
        let aes_key: [u8; 32] = shared_bytes[..32].try_into().map_err(|_| "Bad shared secret")?;

        // AES-256-GCM decrypt
        let cipher = Aes256Gcm::new(&aes_key.into());
        let nonce_bytes = STANDARD.decode(iv_b64).map_err(|e| e.to_string())?;
        let ct_bytes = STANDARD.decode(ct_b64).map_err(|e| e.to_string())?;
        let nonce = Nonce::from_slice(&nonce_bytes);

        let pt = cipher.decrypt(nonce, ct_bytes.as_slice()).map_err(|e| e.to_string())?;
        String::from_utf8(pt).map_err(|e| e.to_string())
    }

    pub fn decrypt(&self, ciphertext_json: &str) -> Result<String, String> {
        Self::decrypt_message_with_key(&self.secret_key, ciphertext_json)
    }

    pub fn get_secret_key(&self) -> &SecretKey {
        &self.secret_key
    }
}
