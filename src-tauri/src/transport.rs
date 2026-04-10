use chacha20::ChaCha20;
use chacha20::cipher::{KeyIvInit, StreamCipher};
use rand::RngCore;

#[derive(Clone)]
pub struct CipherSession {
    pub key: [u8; 32],
}

impl CipherSession {
    pub fn new() -> Self {
        let mut key = [0u8; 32];
        OsRng.fill_bytes(&mut key);
        Self { key }
    }

    pub fn from_hex(hex_str: &str) -> Result<Self, String> {
        let bytes = hex::decode(hex_str).map_err(|e| e.to_string())?;
        if bytes.len() != 32 {
            return Err(format!("Expected 32 bytes, got {}", bytes.len()));
        }
        let mut key = [0u8; 32];
        key.copy_from_slice(&bytes);
        Ok(Self { key })
    }

    pub fn key_hex(&self) -> String {
        hex::encode(&self.key)
    }

    /// Encrypt with ChaCha20 v3 frame
    pub fn encrypt(&self, data: &[u8]) -> Vec<u8> {
        let mut magic = [0u8; 2];
        OsRng.fill_bytes(&mut magic);

        let mut nonce = [0u8; 12];
        OsRng.fill_bytes(&mut nonce);

        let mut ciphertext = data.to_vec();
        let mut cipher = ChaCha20::new(&self.key.into(), &nonce.into());
        cipher.apply_keystream(&mut ciphertext);

        let mut padding_len = 0u8;
        OsRng.fill_bytes(std::slice::from_mut(&mut padding_len));
        let padding_len = (padding_len % 65) as usize;
        let mut padding = vec![0u8; padding_len];
        OsRng.fill_bytes(&mut padding);

        let mut out = Vec::with_capacity(2 + 1 + 12 + ciphertext.len() + padding_len);
        out.extend_from_slice(&magic);
        out.push(0x03); // version
        out.extend_from_slice(&nonce);
        out.extend_from_slice(&ciphertext);
        out.extend_from_slice(&padding);
        out
    }

    /// Decrypt v3 frame
    pub fn decrypt(&self, data: &[u8]) -> Result<Vec<u8>, String> {
        if data.len() < 15 {
            return Err("Frame too short".into());
        }
        if data[2] != 0x03 {
            return Err("Not v3 frame".into());
        }

        let nonce: [u8; 12] = data[3..15].try_into().map_err(|_| "Bad nonce")?;
        let ciphertext = &data[15..];

        if ciphertext.len() > 65536 {
            return Err(format!("Payload too large: {} bytes", ciphertext.len()));
        }

        let mut plaintext = ciphertext.to_vec();
        let mut cipher = ChaCha20::new(&self.key.into(), &nonce.into());
        cipher.apply_keystream(&mut plaintext);

        Ok(plaintext)
    }
}

use rand::rngs::OsRng;
