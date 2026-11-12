use std::fmt;

/// Result type alias using [`Error`].
pub type Result<T> = std::result::Result<T, Error>;

/// Errors returned by the MockServer client.
#[derive(Debug)]
#[non_exhaustive]
pub enum Error {
    /// The server returned a verification failure (HTTP 406).
    VerificationFailure(String),
    /// The server returned an invalid request error (HTTP 400).
    InvalidRequest(String),
    /// The requested feature is disabled on the server (e.g. HTTP 403 from an
    /// SRE control-plane endpoint such as load generation or SLO tracking).
    ///
    /// Enable it via the corresponding server property (e.g.
    /// `loadGenerationEnabled=true`, `sloTrackingEnabled=true`).
    FeatureDisabled(String),
    /// A named resource was not found (HTTP 404), e.g. starting or fetching a
    /// load scenario whose `name` is not registered.
    NotFound(String),
    /// An unexpected HTTP status was returned.
    UnexpectedStatus { status: u16, body: String },
    /// A network or transport error from reqwest.
    Transport(reqwest::Error),
    /// Failed to serialize or deserialize JSON.
    Json(serde_json::Error),
    /// A payload could not be decoded, e.g. a paused stream frame body that is
    /// not valid Base64. The underlying decoder error is available via
    /// [`std::error::Error::source`]; its concrete type is unspecified and may
    /// change.
    Decode(Box<dyn std::error::Error + Send + Sync>),
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::VerificationFailure(msg) => write!(f, "verification failed: {msg}"),
            Error::InvalidRequest(msg) => write!(f, "invalid request (400): {msg}"),
            Error::FeatureDisabled(msg) => {
                write!(f, "feature disabled on server (403): {msg}")
            }
            Error::NotFound(msg) => write!(f, "not found (404): {msg}"),
            Error::UnexpectedStatus { status, body } => {
                write!(f, "unexpected HTTP {status}: {body}")
            }
            Error::Transport(e) => write!(f, "transport error: {e}"),
            Error::Json(e) => write!(f, "JSON error: {e}"),
            Error::Decode(e) => write!(f, "decode error: {e}"),
        }
    }
}

impl std::error::Error for Error {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Error::Transport(e) => Some(e),
            Error::Json(e) => Some(e),
            Error::Decode(e) => Some(e.as_ref()),
            _ => None,
        }
    }
}

impl From<reqwest::Error> for Error {
    fn from(e: reqwest::Error) -> Self {
        Error::Transport(e)
    }
}

impl From<serde_json::Error> for Error {
    fn from(e: serde_json::Error) -> Self {
        Error::Json(e)
    }
}
