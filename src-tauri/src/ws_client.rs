use tungstenite::{Message, client::IntoClientRequest};
use tokio_tungstenite::{connect_async, tungstenite::protocol::Message as WsMessage};
use futures_util::{SinkExt, StreamExt};
use tauri::Emitter;
use std::sync::Arc;
use tokio::sync::{Mutex, mpsc};

use crate::transport::CipherSession;

pub struct WsClient {
    ws_url: String,
    uin: u32,
    token: String,
    session: Arc<Mutex<Option<CipherSession>>>,
    app: tauri::AppHandle,
    tx: Arc<Mutex<Option<mpsc::UnboundedSender<Vec<u8>>>>>,
}

impl WsClient {
    pub fn new(ws_url: String, uin: u32, token: String, app: tauri::AppHandle) -> Self {
        Self {
            ws_url,
            uin,
            token,
            session: Arc::new(Mutex::new(None)),
            app,
            tx: Arc::new(Mutex::new(None)),
        }
    }

    pub async fn connect(&self) -> Result<(), String> {
        let url = self.ws_url.clone();
        let uin = self.uin;
        let token = self.token.clone();
        let session = self.session.clone();
        let app = self.app.clone();
        let tx_store = self.tx.clone();

        let mut request = url.into_client_request().map_err(|e| e.to_string())?;
        request.headers_mut().insert("Sec-WebSocket-Protocol", "chat".parse().unwrap());

        let (ws_stream, _) = connect_async(request).await.map_err(|e| e.to_string())?;
        let (mut write, mut read) = ws_stream.split();

        // Send auth
        let auth = serde_json::json!({"uin": uin, "token": token});
        write.send(WsMessage::Text(auth.to_string().into())).await.map_err(|e| e.to_string())?;

        // Wait for auth_ok
        if let Some(Ok(msg)) = read.next().await {
            match msg {
                WsMessage::Binary(data) => {
                    let session_key = CipherSession::new();
                    let plaintext = if data.len() >= 15 && data[2] == 0x03 {
                        session_key.decrypt(&data).unwrap_or_else(|_| data.to_vec())
                    } else {
                        decode_fallback(&data)
                    };

                    if let Ok(json) = String::from_utf8(plaintext) {
                        if let Ok(auth_ok) = serde_json::from_str::<serde_json::Value>(&json) {
                            if auth_ok["sys"] == "auth_ok" {
                                if let Some(sk) = auth_ok["sk"].as_str() {
                                    *session.lock().await = Some(CipherSession::from_hex(sk).unwrap_or(session_key));
                                } else {
                                    *session.lock().await = Some(session_key);
                                }
                                let _ = app.emit("ws_connected", &json);
                            }
                        }
                    }
                }
                WsMessage::Text(text) => {
                    if let Ok(auth_ok) = serde_json::from_str::<serde_json::Value>(&text) {
                        if auth_ok["sys"] == "auth_ok" {
                            let _ = app.emit("ws_connected", &text);
                        }
                    }
                }
                _ => {}
            }
        }

        // Channel for sending
        let (tx, mut rx) = mpsc::unbounded_channel::<Vec<u8>>();
        *tx_store.lock().await = Some(tx);

        // Read + write loop
        tokio::spawn(async move {
            loop {
                tokio::select! {
                    // Incoming messages
                    Some(Ok(msg)) = read.next() => {
                        match msg {
                            WsMessage::Binary(data) => {
                                let guard = session.lock().await;
                                let plaintext = if let Some(s) = guard.as_ref() {
                                    if data.len() >= 15 && data[2] == 0x03 {
                                        s.decrypt(&data).unwrap_or(data)
                                    } else {
                                        decode_fallback(&data)
                                    }
                                } else {
                                    decode_fallback(&data)
                                };

                                if let Ok(text) = String::from_utf8(plaintext) {
                                    let _ = app.emit("ws_message", &text);
                                }
                            }
                            WsMessage::Text(text) => {
                                let _ = app.emit("ws_message", text.as_str());
                            }
                            WsMessage::Close(_) => {
                                let _ = app.emit("ws_disconnected", "");
                                break;
                            }
                            _ => {}
                        }
                    }
                    // Outgoing messages
                    Some(data) = rx.recv() => {
                        let _ = write.send(WsMessage::Binary(data.into())).await;
                    }
                    // Channel closed
                    else => break,
                }
            }
        });

        Ok(())
    }

    pub async fn send_message(&self, receiver_uin: u32, ciphertext: String) -> Result<(), String> {
        let envelope = serde_json::json!({
            "type": "Message",
            "data": {
                "sender_uin": 0,
                "receiver_uin": receiver_uin,
                "ciphertext": ciphertext,
                "timestamp": std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_secs() as i64
            }
        });

        let session = self.session.lock().await;
        let data = if let Some(s) = session.as_ref() {
            s.encrypt(envelope.to_string().as_bytes())
        } else {
            // Fallback: send as v1 plaintext
            crate::transport::encode_v1(envelope.to_string().as_bytes())
        };

        let tx = self.tx.lock().await;
        if let Some(tx) = tx.as_ref() {
            tx.send(data).map_err(|_| "WS not connected".to_string())
        } else {
            Err("WS sender channel not initialized".to_string())
        }
    }

    pub async fn send_typed(&self, type_name: &str, data: serde_json::Value) -> Result<(), String> {
        let envelope = serde_json::json!({
            "type": type_name,
            "data": data
        });

        let session = self.session.lock().await;
        let bytes = if let Some(s) = session.as_ref() {
            s.encrypt(envelope.to_string().as_bytes())
        } else {
            crate::transport::encode_v1(envelope.to_string().as_bytes())
        };

        let tx = self.tx.lock().await;
        if let Some(tx) = tx.as_ref() {
            tx.send(bytes).map_err(|_| "WS not connected".to_string())
        } else {
            Err("WS sender channel not initialized".to_string())
        }
    }
}

/// Decode v2/v1 fallback frames
fn decode_fallback(data: &[u8]) -> Vec<u8> {
    if data.len() >= 7 && data[0] == 0x49 && data[1] == 0x52 {
        let len = u32::from_le_bytes([data[3], data[4], data[5], data[6]]) as usize;
        if data.len() >= 7 + len {
            return data[7..7+len].to_vec();
        }
    }
    if data.len() >= 8 && data[2] == 0x02 {
        let xor_key = data[3];
        let len = u32::from_le_bytes([data[4], data[5], data[6], data[7]]) as usize;
        if data.len() >= 8 + len {
            let payload = &data[8..8+len];
            return payload.iter().enumerate().map(|(i, &b)| b ^ xor_key.wrapping_add(i as u8)).collect();
        }
    }
    data.to_vec()
}
