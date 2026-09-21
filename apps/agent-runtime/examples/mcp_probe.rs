//! An explicit administrator probe, not an Agent-callable generic network tool.
//! Never forward platform access tokens to an MCP server. Only localhost is accepted here.
use rmcp::{transport::StreamableHttpClientTransport, ServiceExt};
#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let url = std::env::var("OPSWEAVE_MCP_PROBE_URL")?;
    if url != "http://127.0.0.1:8000/mcp" {
        return Err("This spike only permits http://127.0.0.1:8000/mcp".into());
    }
    let transport = StreamableHttpClientTransport::from_uri(url);
    let client = ().serve(transport).await?;
    let tools = client.list_all_tools().await?;
    for tool in tools {
        println!("{}", tool.name);
    }
    client.cancel().await?;
    Ok(())
}
