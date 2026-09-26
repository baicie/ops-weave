use axum::{
    http::StatusCode,
    response::{IntoResponse, Response},
    Json,
};
use serde_json::json;

#[derive(Debug, thiserror::Error)]
pub enum AppError {
    #[error("authentication required")]
    Unauthorized,
    #[error("access denied")]
    Forbidden,
    #[error("invalid input: {0}")]
    Invalid(String),
    #[error("runtime is disabled")]
    Disabled,
    #[error("resource not found")]
    NotFound,
    #[error("runtime capacity reached")]
    Busy,
    #[error("operation timed out")]
    Timeout,
    #[error("model output did not satisfy the contract")]
    InvalidOutput,
    #[error("provider unavailable")]
    Provider,
    #[error("platform read unavailable")]
    Platform,
    #[error("pinned input changed")]
    InputChanged,
    #[error("read session or evidence expired")]
    Expired,
    #[error("invalid server configuration: {0}")]
    Configuration(String),
}
impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let (status, code, message) = match self {
            Self::Unauthorized => (
                StatusCode::UNAUTHORIZED,
                "unauthorized",
                "Authentication required".into(),
            ),
            Self::Forbidden => (StatusCode::FORBIDDEN, "forbidden", "Access denied".into()),
            Self::Invalid(m) => (StatusCode::BAD_REQUEST, "invalid_input", m),
            Self::Disabled => (
                StatusCode::SERVICE_UNAVAILABLE,
                "not_enabled",
                "Runtime is not enabled".into(),
            ),
            Self::NotFound => (
                StatusCode::NOT_FOUND,
                "not_found",
                "Resource not found".into(),
            ),
            Self::Busy => (
                StatusCode::TOO_MANY_REQUESTS,
                "capacity_exceeded",
                "Concurrency limit reached".into(),
            ),
            Self::Timeout => (
                StatusCode::GATEWAY_TIMEOUT,
                "deadline_exceeded",
                "Task deadline exceeded".into(),
            ),
            Self::InvalidOutput => (
                StatusCode::BAD_GATEWAY,
                "invalid_model_output",
                "Output validation failed".into(),
            ),
            Self::Provider => (
                StatusCode::BAD_GATEWAY,
                "provider_error",
                "Provider unavailable".into(),
            ),
            Self::Platform => (
                StatusCode::SERVICE_UNAVAILABLE,
                "platform_unavailable",
                "Platform read unavailable".into(),
            ),
            Self::InputChanged => (
                StatusCode::CONFLICT,
                "input_changed",
                "Pinned input changed".into(),
            ),
            Self::Expired => (
                StatusCode::GONE,
                "expired",
                "Read session or evidence expired".into(),
            ),
            Self::Configuration(_) => (
                StatusCode::INTERNAL_SERVER_ERROR,
                "configuration_error",
                "Server configuration error".into(),
            ),
        };
        (
            status,
            Json(json!({"error":{"code":code,"message":message}})),
        )
            .into_response()
    }
}
