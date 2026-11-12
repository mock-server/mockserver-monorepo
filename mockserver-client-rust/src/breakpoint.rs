//! Breakpoint matcher registration, callback WebSocket client, and handler routing.
//!
//! This module provides the WebSocket callback stack for interactive breakpoints.
//! It connects to `/_mockserver_callback_websocket`, obtains a `clientId`, and
//! dispatches paused items to per-breakpoint-id handlers.

use base64::{engine::general_purpose::STANDARD as BASE64, Engine};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use tungstenite::client::IntoClientRequest;
use tungstenite::Connector;
use tungstenite::Message;
use url::Url;

use crate::error::{Error, Result};
use crate::model::HttpRequest;

// ---------------------------------------------------------------------------
// Breakpoint phase constants
// ---------------------------------------------------------------------------

/// Breakpoint interception phase.
pub mod phase {
    pub const REQUEST: &str = "REQUEST";
    pub const RESPONSE: &str = "RESPONSE";
    pub const RESPONSE_STREAM: &str = "RESPONSE_STREAM";
    pub const INBOUND_STREAM: &str = "INBOUND_STREAM";
}

// ---------------------------------------------------------------------------
// Wire-contract DTOs
// ---------------------------------------------------------------------------

/// Registration request for a breakpoint matcher.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BreakpointMatcherRegistration {
    pub http_request: HttpRequest,
    pub phases: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub client_id: Option<String>,
}

/// Response from registering a breakpoint matcher.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BreakpointMatcherResponse {
    pub id: String,
    pub phases: Vec<String>,
}

/// An entry in the list of registered breakpoint matchers.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BreakpointMatcherEntry {
    pub id: String,
    pub http_request: Value,
    pub phases: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub client_id: Option<String>,
}

/// Response from listing breakpoint matchers.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BreakpointMatcherList {
    pub matchers: Vec<BreakpointMatcherEntry>,
}

/// A paused stream frame pushed by the server over the callback WebSocket.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PausedStreamFrame {
    pub correlation_id: String,
    #[serde(default)]
    pub stream_id: String,
    #[serde(default)]
    pub sequence_number: i64,
    #[serde(default)]
    pub direction: String,
    #[serde(default)]
    pub phase: String,
    #[serde(default)]
    pub body: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub request_method: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub request_path: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub breakpoint_id: Option<String>,
}

impl PausedStreamFrame {
    /// Decode the Base64-encoded body to bytes.
    ///
    /// Returns [`Error::Decode`] when the body is not valid Base64.
    pub fn body_bytes(&self) -> Result<Vec<u8>> {
        BASE64
            .decode(&self.body)
            .map_err(|e| Error::Decode(Box::new(e)))
    }
}

/// Client-to-server reply for a stream frame decision.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StreamFrameDecision {
    pub correlation_id: String,
    pub action: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub body: Option<String>,
}

impl StreamFrameDecision {
    /// Create a CONTINUE decision.
    pub fn continue_frame(correlation_id: impl Into<String>) -> Self {
        Self {
            correlation_id: correlation_id.into(),
            action: "CONTINUE".to_string(),
            body: None,
        }
    }

    /// Create a MODIFY decision with replacement bytes.
    pub fn modify(correlation_id: impl Into<String>, body: &[u8]) -> Self {
        Self {
            correlation_id: correlation_id.into(),
            action: "MODIFY".to_string(),
            body: Some(BASE64.encode(body)),
        }
    }

    /// Create a DROP decision.
    pub fn drop_frame(correlation_id: impl Into<String>) -> Self {
        Self {
            correlation_id: correlation_id.into(),
            action: "DROP".to_string(),
            body: None,
        }
    }

    /// Create an INJECT decision.
    pub fn inject(correlation_id: impl Into<String>, extra_body: &[u8]) -> Self {
        Self {
            correlation_id: correlation_id.into(),
            action: "INJECT".to_string(),
            body: Some(BASE64.encode(extra_body)),
        }
    }

    /// Create a CLOSE decision.
    pub fn close(correlation_id: impl Into<String>) -> Self {
        Self {
            correlation_id: correlation_id.into(),
            action: "CLOSE".to_string(),
            body: None,
        }
    }
}

// ---------------------------------------------------------------------------
// WebSocket message envelope
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WsEnvelope {
    #[serde(rename = "type")]
    pub msg_type: String,
    pub value: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WsClientIdDTO {
    client_id: String,
}

// ---------------------------------------------------------------------------
// Handler types
// ---------------------------------------------------------------------------

/// Handler for REQUEST phase breakpoints.
/// Receives the parsed request JSON. Return a Value that is either:
/// - An HttpRequest JSON object (continue/modify), or
/// - An HttpResponse JSON object with a "statusCode" field (abort).
///
/// Return None to auto-continue with the original request.
pub type BreakpointRequestHandler = Box<dyn Fn(Value) -> Option<Value> + Send + Sync>;

/// Handler for RESPONSE phase breakpoints.
/// Receives the request and response JSON objects.
/// Return a response Value, or None to auto-continue.
pub type BreakpointResponseHandler = Box<dyn Fn(Value, Value) -> Option<Value> + Send + Sync>;

/// Handler for stream frame breakpoints.
/// Receives the parsed PausedStreamFrame.
/// Return a StreamFrameDecision, or None to auto-continue.
pub type BreakpointStreamFrameHandler =
    Box<dyn Fn(&PausedStreamFrame) -> Option<StreamFrameDecision> + Send + Sync>;

/// Handler for object (closure) response callbacks.
///
/// Receives the matched request as JSON and returns the response JSON object.
/// Unlike breakpoint handlers this is mandatory (no auto-continue) — when a
/// request frame without a breakpoint id arrives, the registered closure
/// produces the response that MockServer returns to the caller.
///
/// Only `Send` is required (not `Sync`): the handler is stored behind a `Mutex`
/// and only ever invoked from the single WebSocket-read thread, so access is
/// already serialized. This keeps the public `mock_with_callback` bound to the
/// minimal `Fn(HttpRequest) -> HttpResponse + Send + 'static`.
pub type ObjectResponseHandler = Box<dyn Fn(Value) -> Value + Send>;

// ---------------------------------------------------------------------------
// BreakpointWebSocketClient
// ---------------------------------------------------------------------------

/// Internal WebSocket client for breakpoint callback resolution.
pub(crate) struct BreakpointWebSocketClient {
    pub(crate) client_id: String,
    socket: Arc<Mutex<tungstenite::WebSocket<tungstenite::stream::MaybeTlsStream<std::net::TcpStream>>>>,
    request_handlers: Arc<Mutex<HashMap<String, BreakpointRequestHandler>>>,
    response_handlers: Arc<Mutex<HashMap<String, BreakpointResponseHandler>>>,
    stream_frame_handlers: Arc<Mutex<HashMap<String, BreakpointStreamFrameHandler>>>,
    /// Single object/closure response callback for this WS client (request
    /// frames without an `X-MockServer-BreakpointId` header route here).
    object_response_handler: Arc<Mutex<Option<ObjectResponseHandler>>>,
    dead: Arc<std::sync::atomic::AtomicBool>,
    /// A second handle on the socket's TCP stream. The read loop holds
    /// `socket`'s lock while blocked in `read`, so `close` shuts this down to
    /// end that read instead of waiting for the lock.
    shutdown_handle: Option<std::net::TcpStream>,
    _read_thread: Option<std::thread::JoinHandle<()>>,
}

impl BreakpointWebSocketClient {
    /// Returns true if the read loop has exited (connection no longer usable).
    pub(crate) fn is_dead(&self) -> bool {
        self.dead.load(std::sync::atomic::Ordering::Relaxed)
    }

    /// Connect to the callback WebSocket and obtain a clientId. A non-empty
    /// `authorization` (a whole `Bearer ...` or `Basic ...` value) is sent as the
    /// `Authorization` header on the upgrade.
    /// An `https` base URL connects over `wss` using `tls`, which carries the
    /// client's HTTPS trust roots, custom CA and client identity.
    pub(crate) fn connect(
        base_url: &str,
        authorization: Option<&str>,
        tls: Option<&native_tls::TlsConnector>,
    ) -> Result<Self> {
        let parsed = Url::parse(base_url)
            .map_err(|e| Error::InvalidRequest(format!("bad base URL: {e}")))?;

        let secure = parsed.scheme() == "https";
        let ws_scheme = if secure { "wss" } else { "ws" };
        let host = parsed.host_str().unwrap_or("localhost");
        let port = parsed.port().unwrap_or(if secure { 443 } else { 80 });
        let path = parsed.path().trim_end_matches('/');
        let ws_url = format!("{ws_scheme}://{host}:{port}{path}/_mockserver_callback_websocket");

        let mut request = ws_url
            .as_str()
            .into_client_request()
            .map_err(|e| Error::InvalidRequest(format!("bad WebSocket URL: {e}")))?;
        if let Some(authorization) = authorization.filter(|a| !a.is_empty()) {
            let mut value = tungstenite::http::HeaderValue::from_str(authorization)
                .map_err(|e| Error::InvalidRequest(format!("invalid control-plane authorization: {e}")))?;
            value.set_sensitive(true);
            request
                .headers_mut()
                .insert(tungstenite::http::header::AUTHORIZATION, value);
        }

        // The OS-level TCP connect timeout applies; tungstenite has no handshake timeout.
        let addrs = parsed
            .socket_addrs(|| Some(port))
            .map_err(|e| Error::InvalidRequest(format!("WebSocket connect failed: {e}")))?;
        let stream = std::net::TcpStream::connect(addrs.as_slice())
            .map_err(|e| Error::InvalidRequest(format!("WebSocket connect failed: {e}")))?;
        let shutdown_handle = stream.try_clone().ok();
        let connector = ws_connector(secure, tls)?;
        // client_tls_with_config never follows redirects, which would send the bearer token to another host
        let (mut socket, _response) =
            tungstenite::client_tls_with_config(request, stream, None, Some(connector))
                .map_err(|e| Error::InvalidRequest(format!("WebSocket connect failed: {e}")))?;

        // Read registration reply
        let reg_msg = socket
            .read()
            .map_err(|e| Error::InvalidRequest(format!("WebSocket read registration: {e}")))?;

        let reg_text = match reg_msg {
            Message::Text(t) => t,
            _ => return Err(Error::InvalidRequest("Expected text registration message".into())),
        };

        let envelope: WsEnvelope = serde_json::from_str(&reg_text)?;
        let client_id = if envelope.msg_type
            == "org.mockserver.serialization.model.WebSocketClientIdDTO"
        {
            let dto: WsClientIdDTO = serde_json::from_str(&envelope.value)?;
            dto.client_id
        } else {
            return Err(Error::InvalidRequest(format!(
                "Unexpected registration type: {}",
                envelope.msg_type
            )));
        };

        let request_handlers: Arc<Mutex<HashMap<String, BreakpointRequestHandler>>> =
            Arc::new(Mutex::new(HashMap::new()));
        let response_handlers: Arc<Mutex<HashMap<String, BreakpointResponseHandler>>> =
            Arc::new(Mutex::new(HashMap::new()));
        let stream_frame_handlers: Arc<Mutex<HashMap<String, BreakpointStreamFrameHandler>>> =
            Arc::new(Mutex::new(HashMap::new()));
        let object_response_handler: Arc<Mutex<Option<ObjectResponseHandler>>> =
            Arc::new(Mutex::new(None));

        let socket = Arc::new(Mutex::new(socket));
        let dead = Arc::new(std::sync::atomic::AtomicBool::new(false));

        // Start read loop in a background thread
        let rh = Arc::clone(&request_handlers);
        let resh = Arc::clone(&response_handlers);
        let sfh = Arc::clone(&stream_frame_handlers);
        let orh = Arc::clone(&object_response_handler);
        let sock = Arc::clone(&socket);
        let dead_flag = Arc::clone(&dead);

        let read_thread = std::thread::spawn(move || {
            read_loop(sock, rh, resh, sfh, orh, dead_flag);
        });

        Ok(Self {
            client_id,
            socket,
            request_handlers,
            response_handlers,
            stream_frame_handlers,
            object_response_handler,
            dead,
            shutdown_handle,
            _read_thread: Some(read_thread),
        })
    }

    pub(crate) fn set_request_handler(&self, breakpoint_id: &str, handler: BreakpointRequestHandler) {
        self.request_handlers
            .lock()
            .unwrap()
            .insert(breakpoint_id.to_string(), handler);
    }

    pub(crate) fn set_response_handler(&self, breakpoint_id: &str, handler: BreakpointResponseHandler) {
        self.response_handlers
            .lock()
            .unwrap()
            .insert(breakpoint_id.to_string(), handler);
    }

    pub(crate) fn set_stream_frame_handler(
        &self,
        breakpoint_id: &str,
        handler: BreakpointStreamFrameHandler,
    ) {
        self.stream_frame_handlers
            .lock()
            .unwrap()
            .insert(breakpoint_id.to_string(), handler);
    }

    /// Register (or replace) the single object/closure response callback.
    pub(crate) fn set_object_response_handler(&self, handler: ObjectResponseHandler) {
        *self.object_response_handler.lock().unwrap() = Some(handler);
    }

    pub(crate) fn remove_handlers(&self, breakpoint_id: &str) {
        self.request_handlers.lock().unwrap().remove(breakpoint_id);
        self.response_handlers.lock().unwrap().remove(breakpoint_id);
        self.stream_frame_handlers
            .lock()
            .unwrap()
            .remove(breakpoint_id);
    }

    pub(crate) fn clear_handlers(&self) {
        self.request_handlers.lock().unwrap().clear();
        self.response_handlers.lock().unwrap().clear();
        self.stream_frame_handlers.lock().unwrap().clear();
    }

    pub(crate) fn close(&self) {
        if let Ok(mut sock) = self.socket.try_lock() {
            let _ = sock.close(None);
            let _ = sock.flush();
        }
        if let Some(stream) = &self.shutdown_handle {
            let _ = stream.shutdown(std::net::Shutdown::Both);
        }
    }
}

/// `wss` uses the client's configured TLS connector, or native-tls defaults when none is given.
fn ws_connector(secure: bool, tls: Option<&native_tls::TlsConnector>) -> Result<Connector> {
    match (secure, tls) {
        (false, _) => Ok(Connector::Plain),
        (true, Some(connector)) => Ok(Connector::NativeTls(connector.clone())),
        (true, None) => native_tls::TlsConnector::new()
            .map(Connector::NativeTls)
            .map_err(|e| Error::InvalidRequest(format!("WebSocket TLS setup failed: {e}"))),
    }
}

// ---------------------------------------------------------------------------
// Read loop (runs on a background thread)
// ---------------------------------------------------------------------------

/// Drop guard that sets the `dead` flag when the read loop exits for any reason.
struct ReadLoopDeadGuard(Arc<std::sync::atomic::AtomicBool>);
impl Drop for ReadLoopDeadGuard {
    fn drop(&mut self) {
        self.0.store(true, std::sync::atomic::Ordering::Relaxed);
    }
}

#[allow(clippy::too_many_arguments)]
fn read_loop(
    socket: Arc<Mutex<tungstenite::WebSocket<tungstenite::stream::MaybeTlsStream<std::net::TcpStream>>>>,
    request_handlers: Arc<Mutex<HashMap<String, BreakpointRequestHandler>>>,
    response_handlers: Arc<Mutex<HashMap<String, BreakpointResponseHandler>>>,
    stream_frame_handlers: Arc<Mutex<HashMap<String, BreakpointStreamFrameHandler>>>,
    object_response_handler: Arc<Mutex<Option<ObjectResponseHandler>>>,
    dead: Arc<std::sync::atomic::AtomicBool>,
) {
    let _guard = ReadLoopDeadGuard(dead);
    loop {
        let msg = {
            let mut sock = match socket.lock() {
                Ok(s) => s,
                Err(e) => {
                    eprintln!("mockserver: breakpoint ws read loop terminated: mutex poisoned: {e}");
                    return;
                }
            };
            match sock.read() {
                Ok(m) => m,
                Err(e) => {
                    eprintln!("mockserver: breakpoint ws read loop terminated: {e}");
                    return;
                }
            }
        };

        let text = match msg {
            Message::Text(t) => t,
            Message::Close(_) => {
                eprintln!("mockserver: breakpoint ws read loop terminated: received close frame");
                return;
            }
            _ => continue,
        };

        let envelope: WsEnvelope = match serde_json::from_str(&text) {
            Ok(e) => e,
            Err(_) => continue,
        };

        match envelope.msg_type.as_str() {
            "org.mockserver.model.HttpRequest" => {
                handle_request(
                    &envelope.value,
                    &request_handlers,
                    &object_response_handler,
                    &socket,
                );
            }
            "org.mockserver.model.HttpRequestAndHttpResponse" => {
                handle_response(
                    &envelope.value,
                    &response_handlers,
                    &socket,
                );
            }
            "org.mockserver.serialization.model.PausedStreamFrameDTO" => {
                handle_stream_frame(
                    &envelope.value,
                    &stream_frame_handlers,
                    &socket,
                );
            }
            "org.mockserver.serialization.model.WebSocketClientIdDTO" => {
                // Already handled during connect
            }
            _ => {}
        }
    }
}

fn handle_request(
    value_json: &str,
    handlers: &Arc<Mutex<HashMap<String, BreakpointRequestHandler>>>,
    object_response_handler: &Arc<Mutex<Option<ObjectResponseHandler>>>,
    socket: &Arc<Mutex<tungstenite::WebSocket<tungstenite::stream::MaybeTlsStream<std::net::TcpStream>>>>,
) {
    // A request frame WITHOUT a breakpoint id is an object-callback invocation;
    // route it to the registered closure. A frame WITH a breakpoint id is a
    // paused-breakpoint request and stays on the breakpoint path.
    if let Some((type_name, result)) = route_object_callback(value_json, object_response_handler) {
        send_envelope(socket, &type_name, &result);
        return;
    }
    if let Some((type_name, result)) = route_request(value_json, handlers) {
        send_envelope(socket, &type_name, &result);
    }
}

fn handle_response(
    value_json: &str,
    handlers: &Arc<Mutex<HashMap<String, BreakpointResponseHandler>>>,
    socket: &Arc<Mutex<tungstenite::WebSocket<tungstenite::stream::MaybeTlsStream<std::net::TcpStream>>>>,
) {
    if let Some((type_name, result)) = route_response(value_json, handlers) {
        send_envelope(socket, &type_name, &result);
    }
}

fn handle_stream_frame(
    value_json: &str,
    handlers: &Arc<Mutex<HashMap<String, BreakpointStreamFrameHandler>>>,
    socket: &Arc<Mutex<tungstenite::WebSocket<tungstenite::stream::MaybeTlsStream<std::net::TcpStream>>>>,
) {
    if let Some((type_name, decision)) = route_stream_frame(value_json, handlers) {
        send_envelope(socket, &type_name, &decision);
    }
}

// ---------------------------------------------------------------------------
// Pure routing functions (testable without a live WebSocket)
// ---------------------------------------------------------------------------

/// Route a REQUEST-phase message: dispatch to the per-breakpoint-id handler,
/// auto-continue on missing handler or panic. Returns (type_name, reply_value).
pub fn route_request(
    value_json: &str,
    handlers: &Arc<Mutex<HashMap<String, BreakpointRequestHandler>>>,
) -> Option<(String, Value)> {
    let request: Value = serde_json::from_str(value_json).ok()?;

    let correlation_id = extract_header(&request, "WebSocketCorrelationId");
    let breakpoint_id = extract_header(&request, "X-MockServer-BreakpointId");

    let mut result: Option<Value> = None;
    if let Some(bp_id) = &breakpoint_id {
        if let Ok(h) = handlers.lock() {
            if let Some(handler) = h.get(bp_id) {
                result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    handler(request.clone())
                }))
                .unwrap_or(None);
            }
        }
    }

    // Auto-continue with original request if no result
    let mut result = result.unwrap_or_else(|| request.clone());

    // Determine type: statusCode present => HttpResponse (abort)
    let type_name = if result.get("statusCode").is_some() {
        "org.mockserver.model.HttpResponse"
    } else {
        "org.mockserver.model.HttpRequest"
    };

    if let Some(corr) = &correlation_id {
        set_header(&mut result, "WebSocketCorrelationId", corr);
    }

    Some((type_name.to_string(), result))
}

/// Route an object-callback request frame to the registered closure.
///
/// Object-callback frames are `org.mockserver.model.HttpRequest` messages that
/// carry a `WebSocketCorrelationId` but **no** `X-MockServer-BreakpointId`
/// header (that header marks a paused-breakpoint request, handled elsewhere).
///
/// Returns `None` when the frame is a breakpoint request (has a breakpoint id)
/// or when no object handler is registered — in both cases the caller falls
/// through to the breakpoint request path. On a match, invokes the closure
/// (auto-continuing with a passthrough response on panic), echoes the
/// `WebSocketCorrelationId` onto the reply, and returns
/// `("org.mockserver.model.HttpResponse", response_json)`.
pub fn route_object_callback(
    value_json: &str,
    handler: &Arc<Mutex<Option<ObjectResponseHandler>>>,
) -> Option<(String, Value)> {
    let request: Value = serde_json::from_str(value_json).ok()?;

    // Breakpoint requests carry an explicit breakpoint id — never object-route them.
    if extract_header(&request, "X-MockServer-BreakpointId").is_some() {
        return None;
    }

    let correlation_id = extract_header(&request, "WebSocketCorrelationId");

    // No handler registered: let the breakpoint path handle it (it auto-continues).
    let guard = handler.lock().ok()?;
    let handler = guard.as_ref()?;

    let mut result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        handler(request.clone())
    }))
    .unwrap_or_else(|_| serde_json::json!({}));

    if let Some(corr) = &correlation_id {
        set_header(&mut result, "WebSocketCorrelationId", corr);
    }

    Some(("org.mockserver.model.HttpResponse".to_string(), result))
}

/// Route a RESPONSE-phase message: dispatch to the per-breakpoint-id handler,
/// auto-continue on missing handler or panic. Returns (type_name, reply_value).
pub fn route_response(
    value_json: &str,
    handlers: &Arc<Mutex<HashMap<String, BreakpointResponseHandler>>>,
) -> Option<(String, Value)> {
    let req_and_resp: Value = serde_json::from_str(value_json).ok()?;

    let http_request = req_and_resp.get("httpRequest").cloned().unwrap_or(Value::Null);
    let http_response = req_and_resp.get("httpResponse").cloned().unwrap_or(Value::Null);

    let correlation_id = extract_header(&http_request, "WebSocketCorrelationId");
    let breakpoint_id = extract_header(&http_request, "X-MockServer-BreakpointId");

    let mut result: Option<Value> = None;
    if let Some(bp_id) = &breakpoint_id {
        if let Ok(h) = handlers.lock() {
            if let Some(handler) = h.get(bp_id) {
                result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    handler(http_request.clone(), http_response.clone())
                }))
                .unwrap_or(None);
            }
        }
    }

    let mut result = result.unwrap_or(http_response);

    if let Some(corr) = &correlation_id {
        set_header(&mut result, "WebSocketCorrelationId", corr);
    }

    Some(("org.mockserver.model.HttpResponse".to_string(), result))
}

/// Route a stream-frame message: dispatch to the per-breakpoint-id handler,
/// auto-continue on missing handler or panic. Returns (type_name, decision).
pub fn route_stream_frame(
    value_json: &str,
    handlers: &Arc<Mutex<HashMap<String, BreakpointStreamFrameHandler>>>,
) -> Option<(String, StreamFrameDecision)> {
    let frame: PausedStreamFrame = serde_json::from_str(value_json).ok()?;

    let mut decision: Option<StreamFrameDecision> = None;
    if let Some(bp_id) = &frame.breakpoint_id {
        if let Ok(h) = handlers.lock() {
            if let Some(handler) = h.get(bp_id) {
                decision = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    handler(&frame)
                }))
                .unwrap_or(None);
            }
        }
    }

    let mut decision = decision.unwrap_or_else(|| StreamFrameDecision::continue_frame(&frame.correlation_id));
    decision.correlation_id = frame.correlation_id;

    Some((
        "org.mockserver.serialization.model.StreamFrameDecisionDTO".to_string(),
        decision,
    ))
}

fn send_envelope<T: Serialize>(
    socket: &Arc<Mutex<tungstenite::WebSocket<tungstenite::stream::MaybeTlsStream<std::net::TcpStream>>>>,
    type_name: &str,
    value: &T,
) {
    let value_json = match serde_json::to_string(value) {
        Ok(j) => j,
        Err(_) => return,
    };
    let envelope = WsEnvelope {
        msg_type: type_name.to_string(),
        value: value_json,
    };
    let env_json = match serde_json::to_string(&envelope) {
        Ok(j) => j,
        Err(_) => return,
    };
    if let Ok(mut sock) = socket.lock() {
        let _ = sock.send(Message::text(env_json));
    }
}

// ---------------------------------------------------------------------------
// Header helpers
// ---------------------------------------------------------------------------

/// Extract the first value of a header from a JSON request object.
pub fn extract_header(obj: &Value, name: &str) -> Option<String> {
    let headers = obj.get("headers")?.as_object()?;
    let name_lower = name.to_lowercase();
    for (k, v) in headers {
        if k.to_lowercase() == name_lower {
            if let Some(arr) = v.as_array() {
                return arr.first()?.as_str().map(|s| s.to_string());
            }
            return v.as_str().map(|s| s.to_string());
        }
    }
    None
}

/// Set a header value on a JSON request/response object.
pub fn set_header(obj: &mut Value, name: &str, value: &str) {
    if let Some(obj_map) = obj.as_object_mut() {
        let headers = obj_map
            .entry("headers")
            .or_insert_with(|| Value::Object(serde_json::Map::new()));
        if let Some(h) = headers.as_object_mut() {
            h.insert(
                name.to_string(),
                serde_json::json!([value]),
            );
        }
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use std::net::TcpListener;
    use std::sync::mpsc;
    use std::time::{Duration, Instant};

    /// A callback WebSocket server that registers one client and then stays
    /// silent, so the client's read loop blocks in `read` holding the lock.
    fn idle_callback_server() -> (u16, std::thread::JoinHandle<()>) {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
        let port = listener.local_addr().expect("local addr").port();
        let server = std::thread::spawn(move || {
            let (stream, _) = listener.accept().expect("accept");
            let mut ws = tungstenite::accept(stream).expect("ws handshake");
            let registration = serde_json::json!({
                "type": "org.mockserver.serialization.model.WebSocketClientIdDTO",
                "value": serde_json::json!({ "clientId": "idle-client" }).to_string(),
            });
            ws.send(Message::text(registration.to_string()))
                .expect("send registration");
            while ws.read().is_ok() {}
        });
        (port, server)
    }

    /// A callback WebSocket server that records the upgrade request's
    /// `Authorization` header (`None` when absent), then registers the client.
    pub(crate) fn recording_callback_server() -> (u16, mpsc::Receiver<Option<String>>) {
        recording_server(None)
    }

    /// [`recording_callback_server`] over TLS, presenting `identity`.
    pub(crate) fn recording_tls_callback_server(
        identity: native_tls::Identity,
    ) -> (u16, mpsc::Receiver<Option<String>>) {
        recording_server(Some(native_tls::TlsAcceptor::new(identity).expect("tls acceptor")))
    }

    fn recording_server(
        tls: Option<native_tls::TlsAcceptor>,
    ) -> (u16, mpsc::Receiver<Option<String>>) {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
        let port = listener.local_addr().expect("local addr").port();
        let (tx, rx) = mpsc::channel();
        std::thread::spawn(move || {
            let (stream, _) = listener.accept().expect("accept");
            match tls {
                None => serve_recorded(stream, tx),
                Some(acceptor) => {
                    if let Ok(tls_stream) = acceptor.accept(stream) {
                        serve_recorded(tls_stream, tx);
                    }
                }
            }
        });
        (port, rx)
    }

    fn serve_recorded<S: std::io::Read + std::io::Write>(
        stream: S,
        tx: mpsc::Sender<Option<String>>,
    ) {
        // the error type is fixed by tungstenite's handshake Callback trait
        #[allow(clippy::result_large_err)]
        let record = |request: &tungstenite::handshake::server::Request,
                      response: tungstenite::handshake::server::Response| {
            let authorization = request
                .headers()
                .get("authorization")
                .map(|v| v.to_str().unwrap_or_default().to_string());
            let _ = tx.send(authorization);
            Ok(response)
        };
        let Ok(mut ws) = tungstenite::accept_hdr(stream, record) else {
            return;
        };
        let registration = serde_json::json!({
            "type": "org.mockserver.serialization.model.WebSocketClientIdDTO",
            "value": serde_json::json!({ "clientId": "recorded-client" }).to_string(),
        });
        ws.send(Message::text(registration.to_string()))
            .expect("send registration");
        while ws.read().is_ok() {}
    }

    /// A self-signed certificate for `127.0.0.1` as `(cert PEM, PKCS#8 key PEM)`,
    /// generated with the `openssl` CLI.
    pub(crate) fn self_signed_localhost_cert() -> (Vec<u8>, Vec<u8>) {
        self_signed_cert("127.0.0.1", "IP:127.0.0.1")
    }

    /// A self-signed certificate with the given common name and subjectAltName.
    pub(crate) fn self_signed_cert(common_name: &str, subject_alt_name: &str) -> (Vec<u8>, Vec<u8>) {
        static NEXT: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
        let dir = std::env::temp_dir().join(format!(
            "mockserver-rust-wss-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
        ));
        std::fs::create_dir_all(&dir).expect("create temp dir");
        let (cert, key) = (dir.join("cert.pem"), dir.join("key.pem"));
        let output = std::process::Command::new("openssl")
            .args(["req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1"])
            .arg("-subj")
            .arg(format!("/CN={common_name}"))
            .arg("-addext")
            .arg(format!("subjectAltName={subject_alt_name}"))
            .args(["-addext", "extendedKeyUsage=serverAuth"])
            .arg("-keyout")
            .arg(&key)
            .arg("-out")
            .arg(&cert)
            .output()
            .expect("the wss tests need the openssl CLI");
        assert!(output.status.success(), "openssl failed: {output:?}");
        let pems = (
            std::fs::read(&cert).expect("read cert"),
            std::fs::read(&key).expect("read key"),
        );
        std::fs::remove_dir_all(&dir).ok();
        pems
    }

    fn connector_trusting(cert_pem: &[u8]) -> native_tls::TlsConnector {
        native_tls::TlsConnector::builder()
            .add_root_certificate(native_tls::Certificate::from_pem(cert_pem).expect("cert"))
            .build()
            .expect("connector")
    }

    fn upgrade_authorization(bearer_token: Option<&str>) -> Option<String> {
        let (port, recorded) = recording_callback_server();
        let client =
            BreakpointWebSocketClient::connect(&format!("http://127.0.0.1:{port}"), bearer_token.filter(|t| !t.is_empty()).map(|t| format!("Bearer {t}")).as_deref(), None)
                .expect("connect");
        client.close();
        recorded
            .recv_timeout(Duration::from_secs(3))
            .expect("no upgrade recorded")
    }

    #[test]
    fn upgrade_carries_bearer_token_when_configured() {
        assert_eq!(
            upgrade_authorization(Some("my-jwt-token")),
            Some("Bearer my-jwt-token".to_string())
        );
    }

    #[test]
    fn upgrade_does_not_follow_a_redirect_with_the_bearer_token() {
        use std::io::{Read, Write};
        let elsewhere = TcpListener::bind("127.0.0.1:0").expect("bind");
        let elsewhere_port = elsewhere.local_addr().expect("local addr").port();
        elsewhere.set_nonblocking(true).expect("nonblocking");
        let (contacted_tx, contacted) = mpsc::channel();
        let target = std::thread::spawn(move || {
            let deadline = Instant::now() + Duration::from_secs(2);
            while Instant::now() < deadline {
                if let Ok((stream, _)) = elsewhere.accept() {
                    drop(stream);
                    let _ = contacted_tx.send(());
                    return;
                }
                std::thread::sleep(Duration::from_millis(20));
            }
        });
        let redirecting = TcpListener::bind("127.0.0.1:0").expect("bind");
        let port = redirecting.local_addr().expect("local addr").port();
        let server = std::thread::spawn(move || {
            let (mut stream, _) = redirecting.accept().expect("accept");
            let mut buffer = [0u8; 4096];
            let _ = stream.read(&mut buffer);
            let response = format!(
                "HTTP/1.1 302 Found\r\nLocation: ws://127.0.0.1:{elsewhere_port}/_mockserver_callback_websocket\r\nContent-Length: 0\r\n\r\n"
            );
            let _ = stream.write_all(response.as_bytes());
        });

        let result = BreakpointWebSocketClient::connect(
            &format!("http://127.0.0.1:{port}"),
            Some("Bearer my-jwt-token"),
            None,
        );
        server.join().expect("server thread");
        target.join().expect("redirect target thread");

        assert!(result.is_err(), "a redirect was followed");
        assert!(
            contacted.try_recv().is_err(),
            "the redirect target was contacted"
        );
    }

    #[test]
    fn upgrade_has_no_authorization_without_a_token() {
        assert_eq!(upgrade_authorization(None), None);
        assert_eq!(upgrade_authorization(Some("")), None);
    }

    #[test]
    fn close_returns_promptly_while_read_loop_is_idle() {
        let (port, server) = idle_callback_server();
        let client = Arc::new(
            BreakpointWebSocketClient::connect(&format!("http://127.0.0.1:{port}"), None, None)
                .expect("connect"),
        );
        assert_eq!(client.client_id, "idle-client");
        // Let the read loop take the socket lock and block in `read`.
        std::thread::sleep(Duration::from_millis(300));

        let (done_tx, done_rx) = mpsc::channel();
        let closing = Arc::clone(&client);
        std::thread::spawn(move || {
            closing.close();
            let _ = done_tx.send(());
        });
        assert!(
            done_rx.recv_timeout(Duration::from_secs(3)).is_ok(),
            "close() did not return within 3s while the connection was idle"
        );

        let deadline = Instant::now() + Duration::from_secs(3);
        while !client.is_dead() && Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(20));
        }
        assert!(client.is_dead(), "read loop still running after close()");
        server.join().expect("server thread");
    }

    #[test]
    fn wss_upgrade_uses_the_configured_tls_connector() {
        let (cert, key) = self_signed_localhost_cert();
        let identity = native_tls::Identity::from_pkcs8(&cert, &key).expect("identity");
        let (port, recorded) = recording_tls_callback_server(identity);
        let connector = connector_trusting(&cert);

        let client = BreakpointWebSocketClient::connect(
            &format!("https://127.0.0.1:{port}"),
            Some("Bearer my-jwt-token"),
            Some(&connector),
        )
        .expect("wss connect");
        assert_eq!(client.client_id, "recorded-client");
        client.close();
        assert_eq!(
            recorded.recv_timeout(Duration::from_secs(3)).expect("no upgrade recorded"),
            Some("Bearer my-jwt-token".to_string())
        );
    }

    #[test]
    fn wss_upgrade_rejects_a_certificate_the_connector_does_not_trust() {
        let (cert, key) = self_signed_localhost_cert();
        let identity = native_tls::Identity::from_pkcs8(&cert, &key).expect("identity");
        let (port, recorded) = recording_tls_callback_server(identity);

        let result = BreakpointWebSocketClient::connect(
            &format!("https://127.0.0.1:{port}"),
            Some("Bearer my-jwt-token"),
            None,
        );
        assert!(result.is_err(), "an untrusted certificate was accepted");
        assert!(
            recorded.recv_timeout(Duration::from_millis(500)).is_err(),
            "the upgrade request (and its bearer token) reached an untrusted server"
        );
    }

    #[test]
    fn ws_connector_matches_the_base_url_scheme() {
        let configured = native_tls::TlsConnector::new().expect("connector");
        assert!(matches!(ws_connector(false, Some(&configured)), Ok(Connector::Plain)));
        assert!(matches!(ws_connector(true, Some(&configured)), Ok(Connector::NativeTls(_))));
        assert!(matches!(ws_connector(true, None), Ok(Connector::NativeTls(_))));
    }
}
