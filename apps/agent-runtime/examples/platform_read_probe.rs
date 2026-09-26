//! Administrator-run local acceptance. Does not expose a runtime endpoint or invoke a model.
use chrono::DateTime;
use opsweave_agent_runtime::{adapters::platform_http::PlatformHttp, domain::TimeRange};
use std::env;
fn required(name: &str) -> Result<String, &'static str> {
    env::var(name).map_err(|_| "Missing explicit probe environment")
}
#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let platform = PlatformHttp::loopback(&required("OPSWEAVE_PLATFORM_URL")?)?;
    let from = DateTime::from_timestamp(required("OPSWEAVE_PROBE_FROM")?.parse()?, 0)
        .ok_or("Invalid from")?;
    let to = DateTime::from_timestamp(required("OPSWEAVE_PROBE_TILL")?.parse()?, 0)
        .ok_or("Invalid till")?;
    let read = platform
        .open(
            &required("OPSWEAVE_PLATFORM_TOKEN")?,
            &required("OPSWEAVE_PROBE_INCIDENT")?,
            &TimeRange { from, to },
        )
        .await?;
    if read.storage() != "postgres" {
        return Err("Probe requires real local PostgreSQL".into());
    }
    let (incident, metric) =
        tokio::try_join!(read.incident(), read.metric("host.cpu.usage.user", 500))?;
    if metric.document()["data"]["sampleCount"] != 2
        || metric.document()["dataModes"] != serde_json::json!(["labeled-fixture"])
    {
        return Err("Expected explicit synthetic samples from local VictoriaMetrics".into());
    }
    tokio::try_join!(read.recheck(&incident), read.recheck(&metric))?;
    println!("PASS: Rust HTTP adapter -> Java trusted read session -> PostgreSQL evidence + VictoriaMetrics samples -> fresh authorization rechecks (labeled fixture, current knowledge). No model invoked.");
    Ok(())
}
