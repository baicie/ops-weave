package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestEnvLiteralAndSafeErrors(t *testing.T) {
	values, err := parseEnv("\ufeff# comment\nexport TEST='$(do-not-run)'\nURL=http://localhost/a#b\nTOKEN=literal # comment\nQUOTED=\"space value\"\r\n")
	if err != nil || values["TEST"] != "$(do-not-run)" || values["URL"] != "http://localhost/a#b" || values["TOKEN"] != "literal" || values["QUOTED"] != "space value" {
		t.Fatalf("unexpected parse: %v", err)
	}
	for _, line := range []string{"token_without_equals", "TOKEN='do-not-expose", "A-B=do-not-expose"} {
		_, err := parseEnv(line)
		if err == nil || strings.Contains(err.Error(), "do-not-expose") {
			t.Fatal("invalid or unsafe error")
		}
	}
}

func TestDemoIsExplicitAndStable(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, ".env.dev.platform"), []byte("OPSWEAVE_PROVIDER=rig-openai\nOPSWEAVE_ZABBIX_MODE=jsonrpc\n"), 0600); err != nil {
		t.Fatal(err)
	}
	inherited := map[string]string{"OPSWEAVE_PROVIDER": "rig-openai", "OPSWEAVE_MODE": "platform-dev", "OPSWEAVE_HISTORY_ENABLED": "true", "OPENAI_API_KEY": "test-only-secret"}
	first, err := makeSpec(root, "platform", "demo", inherited)
	if err != nil {
		t.Fatal(err)
	}
	second, err := makeSpec(root, "runtime", "demo", inherited)
	if err != nil {
		t.Fatal(err)
	}
	if first.Env["OPSWEAVE_ZABBIX_MODE"] != "fixture" || first.Env["OPSWEAVE_INVENTORY_STORE"] != "memory" || second.Env["OPSWEAVE_MODE"] != "demo" || second.Env["OPSWEAVE_PROVIDER"] != "mock" || second.Env["OPSWEAVE_HISTORY_ENABLED"] != "false" || second.Env["OPENAI_API_KEY"] != "" {
		t.Fatal("demo accepted live settings")
	}
	if first.Env["OPSWEAVE_DEV_TOKEN"] != second.Env["OPSWEAVE_DEV_TOKEN"] || len(first.Env["OPSWEAVE_DEV_TOKEN"]) < 32 {
		t.Fatal("token changed across components")
	}
	if _, err := makeSpec(t.TempDir(), "platform", "local", nil); err == nil {
		t.Fatal("missing real database silently accepted")
	}
	if _, err := makeSpec(t.TempDir(), "platform", "local", map[string]string{"OPSWEAVE_AUTH_MODE": "dev", "OPSWEAVE_DEV_TOKEN": "REPLACE_WITH_RANDOM_VALUE_AT_LEAST_32_CHARACTERS", "OPSWEAVE_INVENTORY_STORE": "memory"}); err == nil {
		t.Fatal("public placeholder accepted as a development credential")
	}
}

func TestCommandsDoNotUseShellAndPinLoopback(t *testing.T) {
	root := filepath.Join(t.TempDir(), "repo with spaces")
	s, err := makeSpec(root, "platform", "local", map[string]string{"OPSWEAVE_INVENTORY_STORE": "memory", "JAVA_HOME": "/jdk path", "SERVER_ADDRESS": "0.0.0.0", "OPENAI_API_KEY": "test-secret"})
	if err != nil {
		t.Fatal(err)
	}
	if s.Env["OPENAI_API_KEY"] != "" || s.Build.Args[2] != "org.gradle.wrapper.GradleWrapperMain" || !strings.Contains(strings.Join(s.Run.Args, " "), "--server.address=127.0.0.1") {
		t.Fatal("unsafe platform command")
	}
	runtime, err := makeSpec(root, "runtime", "local", map[string]string{"OPSWEAVE_PROVIDER": "rig-openai", "OPSWEAVE_LISTEN": "0.0.0.0:8090"})
	if err != nil {
		t.Fatal(err)
	}
	if runtime.Env["OPSWEAVE_LISTEN"] != "127.0.0.1:8090" || !strings.Contains(strings.Join(runtime.Build.Args, " "), "--locked --features rig-provider") {
		t.Fatal("unsafe runtime command")
	}
}

func TestStartCannotSilentlyChangeProfile(t *testing.T) {
	d := &daemon{root: t.TempDir(), services: map[string]*service{"platform": {spec: spec{Profile: "local"}, row: row{State: "running"}}}}
	if err := d.start(request{Target: "platform", Profile: "demo"}); err == nil || !strings.Contains(err.Error(), "restart") {
		t.Fatal("start hid an already running live profile behind --demo")
	}
	if d.services["platform"].row.State != "running" {
		t.Fatal("profile check mutated the existing service")
	}
}

func TestLogRedactionAcrossWritesAndLongLines(t *testing.T) {
	filename := filepath.Join(t.TempDir(), "component.log")
	log, err := newLog(filename, map[string]string{"OPSWEAVE_DEV_TOKEN": "test-token-123456", "OPENAI_API_KEY": "test-key-654321"})
	if err != nil {
		t.Fatal(err)
	}
	for _, part := range []string{"test-token-", "123456 and test-key-654321\nAuthorization: Bearer another-secret\n", strings.Repeat("x", 70000), "test-token-123456\n", "safe final line"} {
		if _, err := log.Write([]byte(part)); err != nil {
			t.Fatal(err)
		}
	}
	if err := log.Close(); err != nil {
		t.Fatal(err)
	}
	b, _ := os.ReadFile(filename)
	text := string(b)
	if strings.Contains(text, "test-token") || strings.Contains(text, "test-key") || strings.Contains(text, "another-secret") || len(text) > 1000 || !strings.Contains(text, "safe final line") {
		t.Fatal("log leaked credentials or unbounded line")
	}
}

func TestControlAuthenticationAndRequestBoundary(t *testing.T) {
	d := &daemon{root: t.TempDir(), token: randomToken(), services: map[string]*service{}, quit: make(chan struct{})}
	for _, test := range []struct {
		body, auth string
		code       int
	}{{`{"Action":"status"}`, "", 401}, {`{"Action":"status","unexpected":1}`, "Bearer " + d.token, 400}, {`{"Action":"start","Target":"../../outside","Profile":"demo"}`, "Bearer " + d.token, 200}} {
		r := httptest.NewRequest("POST", "/control", strings.NewReader(test.body))
		r.Header.Set("Authorization", test.auth)
		w := httptest.NewRecorder()
		d.ServeHTTP(w, r)
		if w.Code != test.code {
			t.Fatalf("code %d wanted %d", w.Code, test.code)
		}
		if test.code == 200 {
			var reply response
			if json.Unmarshal(w.Body.Bytes(), &reply) != nil || reply.Error == "" || len(d.services) != 0 {
				t.Fatal("invalid target created a process")
			}
		}
	}
}

func freePort(t *testing.T) int {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port
}
func eventually(t *testing.T, condition func() bool) {
	t.Helper()
	deadline := time.Now().Add(8 * time.Second)
	for time.Now().Before(deadline) {
		if condition() {
			return
		}
		time.Sleep(30 * time.Millisecond)
	}
	t.Fatal("condition did not become true")
}

func helperCommand(t *testing.T, root string) command {
	t.Helper()
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	return command{exe, []string{"-test.run=^TestHelperProcess$"}, root}
}

func TestServiceStartStopRestartAndQueuedCancel(t *testing.T) {
	root := t.TempDir()
	os.MkdirAll(filepath.Join(root, ".tmp/dev"), 0700)
	d := &daemon{root: root, services: map[string]*service{}}
	port := freePort(t)
	newService := func() *service {
		ctx, cancel := context.WithCancel(context.Background())
		return &service{spec: spec{ID: "web", Port: port, Health: "/", Env: map[string]string{"OPSWEAVE_TEST_HELPER": "server", "OW_TEST_PORT": strconv.Itoa(port)}, Run: helperCommand(t, root)}, row: row{ID: "web", State: "queued", Port: port}, ctx: ctx, cancel: cancel, done: make(chan struct{})}
	}
	for i := 0; i < 2; i++ {
		s := newService()
		d.mu.Lock()
		d.services["web"] = s
		d.mu.Unlock()
		d.runService(s)
		eventually(t, func() bool { d.mu.Lock(); defer d.mu.Unlock(); return s.row.State == "running" })
		if !processAlive(s.process.cmd.Process.Pid) {
			t.Fatal("owned process missing")
		}
		if err := d.stop("web"); err != nil {
			t.Fatal(err)
		}
		eventually(t, func() bool { return !portBusy(port) })
		d.mu.Lock()
		state, pid := s.row.State, s.row.PID
		d.mu.Unlock()
		if state != "stopped" || pid != 0 {
			t.Fatal("stop did not clear live state")
		}
	}
	s := newService()
	d.mu.Lock()
	d.services["web"] = s
	d.mu.Unlock()
	if err := d.stop("web"); err != nil {
		t.Fatal(err)
	}
	d.runService(s)
	if portBusy(port) {
		t.Fatal("cancelled queued service launched")
	}
}

func TestProcessTreeTermination(t *testing.T) {
	root := t.TempDir()
	port := freePort(t)
	log, err := newLog(filepath.Join(root, "fixture.log"), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer log.Close()
	env := inheritedEnv()
	merge(env, map[string]string{"OPSWEAVE_TEST_HELPER": "parent", "OW_TEST_PORT": strconv.Itoa(port), "OW_TEST_PID_FILE": filepath.Join(root, "child.pid")})
	p, err := launch(helperCommand(t, root), env, log)
	if err != nil {
		t.Fatal(err)
	}
	defer stopTree(p)
	eventually(t, func() bool { return probe(port, "/") == 200 })
	if err := stopTree(p); err != nil {
		t.Fatal(err)
	}
	eventually(t, func() bool { return !portBusy(port) })
}

func TestBuildQueueIsSharedAndCancellable(t *testing.T) {
	root := t.TempDir()
	os.MkdirAll(filepath.Join(root, ".tmp/dev"), 0700)
	d := &daemon{root: root, services: map[string]*service{}}
	create := func(id string) *service {
		ctx, cancel := context.WithCancel(context.Background())
		build := helperCommand(t, root)
		return &service{ctx: ctx, cancel: cancel, done: make(chan struct{}),
			spec: spec{ID: id, Env: map[string]string{"OPSWEAVE_TEST_HELPER": "server", "OW_TEST_PORT": strconv.Itoa(freePort(t))}, Build: &build},
			row:  row{ID: id, State: "queued"}}
	}
	first, second := create("platform"), create("worker")
	d.services["platform"], d.services["worker"] = first, second
	go d.runService(first)
	eventually(t, func() bool {
		d.mu.Lock()
		defer d.mu.Unlock()
		return first.row.State == "building" && first.process != nil
	})
	go d.runService(second)
	// With the first build holding the shared limit, the second cannot spawn.
	time.Sleep(100 * time.Millisecond)
	d.mu.Lock()
	queued := second.row.State == "queued" && second.process == nil
	d.mu.Unlock()
	if !queued {
		t.Fatal("a second terminal bypassed the shared build limit")
	}
	if err := d.stop("worker"); err != nil {
		t.Fatal(err)
	}
	if err := d.stop("platform"); err != nil {
		t.Fatal(err)
	}
	eventually(t, func() bool {
		d.mu.Lock()
		defer d.mu.Unlock()
		return first.row.State == "stopped" && second.row.State == "stopped"
	})
}

func TestForeignPortIsNeverStopped(t *testing.T) {
	l, err := net.Listen("tcp", "127.0.0.1:8080")
	// An already occupied application port exercises the same refusal path.
	if err == nil {
		defer l.Close()
	}
	root := t.TempDir()
	d := &daemon{root: root, services: map[string]*service{}}
	err = d.start(request{Action: "start", Target: "platform", Profile: "local", Env: map[string]string{"OPSWEAVE_INVENTORY_STORE": "memory"}})
	if err == nil || !strings.Contains(err.Error(), "已占用") || len(d.services) != 0 {
		t.Fatal("foreign process was adopted")
	}
	if err := d.stop("all"); err != nil {
		t.Fatal(err)
	}
	if !portBusy(8080) {
		t.Fatal("foreign port was stopped")
	}
}

func TestBuildFailureHasNoRetryOrFallback(t *testing.T) {
	root := t.TempDir()
	os.MkdirAll(filepath.Join(root, ".tmp/dev"), 0700)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	build := helperCommand(t, root)
	s := &service{ctx: ctx, cancel: cancel, done: make(chan struct{}), spec: spec{ID: "web", Env: map[string]string{"OPSWEAVE_TEST_HELPER": "failure"}, Build: &build, Run: helperCommand(t, root)}, row: row{ID: "web", State: "queued"}}
	d := &daemon{root: root, services: map[string]*service{"web": s}}
	d.runService(s)
	if s.row.State != "failed" || s.row.PID != 0 || !strings.Contains(s.row.Error, "构建失败") {
		t.Fatal("build failure did not fail closed")
	}
}

func TestHelperProcess(t *testing.T) {
	mode := os.Getenv("OPSWEAVE_TEST_HELPER")
	if mode == "" {
		return
	}
	if mode == "failure" {
		fmt.Println("explicit fixture build failure")
		os.Exit(7)
	}
	if mode == "parent" {
		exe, _ := os.Executable()
		cmd := exec.Command(exe, "-test.run=^TestHelperProcess$")
		env := inheritedEnv()
		env["OPSWEAVE_TEST_HELPER"] = "server"
		for k, v := range env {
			cmd.Env = append(cmd.Env, k+"="+v)
		}
		// Inherit the parent's Unix process group so the tree test covers descendants.
		if err := cmd.Start(); err != nil {
			os.Exit(3)
		}
		os.WriteFile(os.Getenv("OW_TEST_PID_FILE"), []byte(strconv.Itoa(cmd.Process.Pid)), 0600)
		cmd.Wait()
		os.Exit(0)
	}
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(200)
		fmt.Fprint(w, "explicit test fixture")
	})
	err := http.ListenAndServe("127.0.0.1:"+os.Getenv("OW_TEST_PORT"), handler)
	if err != nil {
		os.Exit(4)
	}
	os.Exit(0)
}
