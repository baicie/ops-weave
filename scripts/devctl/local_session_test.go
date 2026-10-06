package main

import (
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLocalBrowserSessionUsesPlatformConfiguration(t *testing.T) {
	root := t.TempDir()
	token := strings.Repeat("fixture-platform-secret-", 3)
	if err := os.WriteFile(filepath.Join(root, ".env.dev.platform"), []byte("OPSWEAVE_AUTH_MODE=dev\nOPSWEAVE_DEV_TOKEN="+token+"\n"), 0600); err != nil {
		t.Fatal(err)
	}
	actual, err := localBrowserToken(root, "local", map[string]string{"OPENAI_API_KEY": "fixture-unused"})
	if err != nil || actual != token {
		t.Fatal("platform configuration not selected")
	}
	override := strings.Repeat("terminal-fixture-", 3)
	actual, err = localBrowserToken(root, "local", map[string]string{"OPSWEAVE_DEV_TOKEN": override})
	if err != nil || actual != override {
		t.Fatal("terminal precedence not honored")
	}
	for _, env := range []map[string]string{{"VITE_PLATFORM_AUTH": "oidc"}, {"OPSWEAVE_WEB_LOCAL_SESSION": "false"}, {"OPSWEAVE_AUTH_MODE": "closed"}} {
		actual, err = localBrowserToken(root, "local", env)
		if err != nil || actual != "" {
			t.Fatal("explicit session mode ignored")
		}
	}
	_, err = localBrowserToken(root, "local", map[string]string{"OPSWEAVE_DEV_TOKEN": "fixture-invalid"})
	if err == nil || strings.Contains(err.Error(), "fixture-invalid") {
		t.Fatal("invalid credential or unsafe error")
	}
}

func TestStatusDoesNotRelabelOwnedStartupAsUnmanaged(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(200) }))
	defer server.Close()
	port := server.Listener.Addr().(*net.TCPAddr).Port
	d := daemon{services: map[string]*service{"web": {row: row{ID: "web", State: "queued", Port: port}}}}
	for _, r := range d.snapshot() {
		if r.ID == "web" && r.State != "queued" {
			t.Fatal("owned startup was mislabeled as unmanaged")
		}
	}
}

func TestLocalBrowserDemoUsesStableExplicitCredential(t *testing.T) {
	root := t.TempDir()
	a, err := localBrowserToken(root, "demo", nil)
	if err != nil || len(a) < 32 {
		t.Fatal("explicit demo credential missing")
	}
	b, err := localBrowserToken(root, "demo", nil)
	if err != nil || a != b {
		t.Fatal("demo credential changed")
	}
	s, err := makeSpec(root, "worker", "demo", map[string]string{"OPSWEAVE_WEB_LOCAL_TOKEN": a})
	if err != nil || s.Env["OPSWEAVE_WEB_LOCAL_TOKEN"] != "" {
		t.Fatal("web credential leaked into another unit")
	}
	if err := os.WriteFile(filepath.Join(root, ".env.dev.web"), []byte("VITE_PLATFORM_AUTH=oidc\n"), 0600); err != nil {
		t.Fatal(err)
	}
	c, err := localBrowserToken(root, "demo", nil)
	if err != nil || c != "" {
		t.Fatal("web OIDC configuration ignored")
	}
}
