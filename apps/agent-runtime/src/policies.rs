use crate::{
    domain::{DiagnoseRequest, Principal},
    error::AppError,
};
use chrono::{Duration, Utc};
use std::sync::atomic::{AtomicUsize, Ordering};

pub fn authorize(principal: &Principal, incident: &str, permission: &str) -> Result<(), AppError> {
    if !principal.permissions.contains(permission)
        || !principal.allowed_incidents.contains(incident)
    {
        return Err(AppError::Forbidden);
    }
    Ok(())
}
pub fn validate_request(request: &DiagnoseRequest) -> Result<(), AppError> {
    if request.incident_id.is_empty() || request.incident_id.len() > 128 {
        return Err(AppError::Invalid("Invalid incident identifier".into()));
    }
    if request.question.trim().is_empty() || request.question.chars().count() > 2000 {
        return Err(AppError::Invalid(
            "Question must contain 1–2000 characters".into(),
        ));
    }
    if request.time_range.from >= request.time_range.to || request.time_range.to > request.as_of {
        return Err(AppError::Invalid("Require from < to <= asOf".into()));
    }
    if request.time_range.to - request.time_range.from > Duration::hours(24) {
        return Err(AppError::Invalid(
            "Maximum analysis window is 24 hours".into(),
        ));
    }
    if request.as_of > Utc::now() {
        return Err(AppError::Invalid("asOf is in the future".into()));
    }
    Ok(())
}
/// Shared atomic budget: parallel tools cannot each spend an independent copy.
pub struct CallBudget {
    remaining: AtomicUsize,
}
impl CallBudget {
    pub fn new(max: usize) -> Self {
        Self {
            remaining: AtomicUsize::new(max),
        }
    }
    pub fn consume(&self) -> Result<(), AppError> {
        self.remaining
            .fetch_update(Ordering::SeqCst, Ordering::SeqCst, |n| n.checked_sub(1))
            .map(|_| ())
            .map_err(|_| AppError::Invalid("Tool budget exhausted".into()))
    }
}
