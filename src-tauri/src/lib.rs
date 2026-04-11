use serde::{Deserialize, Serialize};
use std::sync::Arc;
use tokio::sync::mpsc;

mod crypto_mgr;
mod transport;
mod ws_client;

pub use crypto_mgr::CryptoManager;
pub use transport::CipherSession;
pub use ws_client::WsClient;

// ── Tauri commands ────────────────────────────────────────────────────────────

#[tauri::command]
fn greet(name: &str) -> String {
    format!("Hello, {}! You've been greeted from Rust!", name)
}

#[tauri::command]
async fn cmd_register(state: tauri::State<'_, AppState>, phone: String, pin: String, public_key: String) -> Result<String, String> {
    let url = format!("{}/api/register", state.base_url);
    let client = reqwest::Client::new();
    let resp = client.post(&url)
        .json(&serde_json::json!({"phone": phone, "pin": pin, "public_key": public_key}))
        .send().await.map_err(|e| e.to_string())?;
    let body = resp.text().await.map_err(|e| e.to_string())?;
    Ok(body)
}

#[tauri::command]
async fn cmd_login(state: tauri::State<'_, AppState>, uin: u32, pin: String) -> Result<String, String> {
    let url = format!("{}/api/login", state.base_url);
    let client = reqwest::Client::new();
    let resp = client.post(&url)
        .json(&serde_json::json!({"uin": uin, "pin": pin}))
        .send().await.map_err(|e| e.to_string())?;
    let body = resp.text().await.map_err(|e| e.to_string())?;
    Ok(body)
}

#[tauri::command]
async fn cmd_connect_ws(state: tauri::State<'_, AppState>, app: tauri::AppHandle, uin: u32, token: String) -> Result<(), String> {
    let ws_url = state.ws_url.clone();
    let token_clone = token.clone();
    let app_clone = app.clone();

    // Create WS client
    let client = WsClient::new(ws_url, uin, token, app);
    client.connect().await?;

    // Store in state
    state.ws_clients.entry(uin).or_insert_with(|| Arc::new(client));

    Ok(())
}

#[tauri::command]
async fn cmd_send_message(state: tauri::State<'_, AppState>, uin: u32, receiver_uin: u32, ciphertext: String) -> Result<(), String> {
    if let Some(client) = state.ws_clients.get(&uin) {
        client.send_message(receiver_uin, ciphertext).await
    } else {
        Err("Not connected".into())
    }
}

#[tauri::command]
fn cmd_generate_keys() -> Result<String, String> {
    let mgr = CryptoManager::new();
    Ok(serde_json::json!({
        "public_key": mgr.public_key_base64(),
        "wrapped_key": "stored_locally"
    }).to_string())
}

#[tauri::command]
fn cmd_encrypt_message(state: tauri::State<'_, AppState>, _uin: u32, recipient_pubkey: String, plaintext: String) -> Result<String, String> {
    CryptoManager::encrypt_message(&plaintext, &recipient_pubkey)
}

#[tauri::command]
fn cmd_decrypt_message(_state: tauri::State<'_, AppState>, _uin: u32, sender_pubkey: String, ciphertext: String, secret_key_b64: String) -> Result<String, String> {
    let mgr = CryptoManager::from_secret_key_base64(&secret_key_b64)?;
    CryptoManager::decrypt_message_with_key(&mgr.secret_key, &ciphertext)
}

// ── App State ─────────────────────────────────────────────────────────────────

use dashmap::DashMap;
use std::sync::Arc;
use tokio::sync::mpsc;

pub struct AppState {
    pub base_url: String,
    pub ws_url: String,
    pub ws_clients: DashMap<u32, Arc<WsClient>>,
}

// ── Tauri setup ───────────────────────────────────────────────────────────────

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .manage(AppState {
            base_url: "https://iromashka.ru".to_string(),
            ws_url: "wss://iromashka.ru/chat".to_string(),
            ws_clients: DashMap::new(),
        })
        .invoke_handler(tauri::generate_handler![
            greet,
            cmd_register,
            cmd_login,
            cmd_connect_ws,
            cmd_send_message,
            cmd_generate_keys,
            cmd_encrypt_message,
            cmd_decrypt_message,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
