use tungstenite::{Message, client::IntoClientRequest};
use tokio_tungstenite::{connect_async, tungstenite::protocol::Message as WsMessage};
use futures_util::{SinkExt, StreamExt};
use tauri::Emitter;
use std::sync::Arc;
use tokio::sync::Mutex;

use crate::transport::CipherSession;

pub struct WsClient {
    ws_url: String,
    uin: u32,
    token: String,
    session: Arc<Mutex<Option<CipherSession>>>,
    app: tauri::AppHandle,
}

impl WsClient {
    pub fn new(ws_url: String, uin: u32, token: String, app: tauri::AppHandle) -> Self {
        Self {
            ws_url,
            uin,
            token,
            session: Arc::new(Mutex::new(None)),
            app,
        }
    }

    pub async fn connect(&self) -> Result<(), String> {
        let url = self.ws_url.clone();
        let uin = self.uin;
        let token = self.token.clone();
        let session = self.session.clone();
        let app = self.app.clone();

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
                    // Try v3 first, then fallback
                    let session_key = CipherSession::new();
                    let plaintext = if data.len() >= 15 && data[2] == 0x03 {
                        session_key.decrypt(&data).unwrap_or_else(|_| data.to_vec())
                    } else {
                        // v2/v1 fallback - decode manually
                        if data.len() >= 7 && data[0] == 0x49 && data[1] == 0x52 {
                            let len = u32::from_le_bytes([data[3], data[4], data[5], data[6]]) as usize;
                            data[7..7+len.min(data.len()-7)].to_vec()
                        } else if data.len() >= 8 && data[2] == 0x02 {
                            let xor_key = data[3];
                            let len = u32::from_le_bytes([data[4], data[5], data[6], data[7]]) as usize;
                            let payload = &data[8..8+len.min(data.len()-8)];
                            payload.iter().enumerate().map(|(i, &b)| b ^ xor_key.wrapping_add(i as u8)).collect()
                        } else {
                            data.to_vec()
                        }
                    };

                    if let Ok(json) = String::from_utf8(plaintext) {
                        if let Ok(auth_ok) = serde_json::from_str::<serde_json::Value>(&json) {
                            if auth_ok["sys"] == "auth_ok" {
                                // Extract session key
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

        // Read loop
        tokio::spawn(async move {
            while let Some(Ok(msg)) = read.next().await {
                match msg {
                    WsMessage::Binary(data) => {
                        let guard = session.lock().await;
                        let plaintext = if let Some(s) = guard.as_ref() {
                            if data.len() >= 15 && data[2] == 0x03 {
                                s.decrypt(&data).unwrap_or(data)
                            } else {
                                // v2/v1 fallback
                                if data.len() >= 7 && data[0] == 0x49 && data[1] == 0x52 {
                                    let len = u32::from_le_bytes([data[3], data[4], data[5], data[6]]) as usize;
                                    data[7..7+len.min(data.len()-7)].to_vec()
                                } else if data.len() >= 8 && data[2] == 0x02 {
                                    let xor_key = data[3];
                                    let len = u32::from_le_bytes([data[4], data[5], data[6], data[7]]) as usize;
                                    let payload = &data[8..8+len.min(data.len()-8)];
                                    payload.iter().enumerate().map(|(i, &b)| b ^ xor_key.wrapping_add(i as u8)).collect()
                                } else {
                                    data
                                }
                            }
                        } else {
                            data
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
                "timestamp": 0
            }
        });

        let session = self.session.lock().await;
        if let Some(s) = session.as_ref() {
            let frame = s.encrypt(envelope.to_string().as_bytes());
            // Send via Tauri event - actual WS send happens in the connect task
            // For now, we store the message to be sent
            let _ = self.app.emit("ws_send_binary", &serde_json::json!({
                "data": hex::encode(&frame)
            }));
        }
        Ok(())
    }
}
