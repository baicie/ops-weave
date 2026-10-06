package main

import (
	"bufio"
	"crypto/rand"
	"encoding/base64"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

var serviceOrder = []string{"platform", "runtime", "worker", "web"}

type command struct {
	Bin  string
	Args []string
	Dir  string
}
type spec struct {
	ID, Label, Mode, Health, Readiness, Profile string
	Port                                        int
	Env                                         map[string]string
	Build                                       *command
	Run                                         command
}

func randomToken() string {
	b := make([]byte, 48)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return base64.RawURLEncoding.EncodeToString(b)
}

func parseEnv(text string) (map[string]string, error) {
	values := map[string]string{}
	scanner := bufio.NewScanner(strings.NewReader(strings.TrimPrefix(text, "\ufeff")))
	scanner.Buffer(make([]byte, 4096), 65536)
	line := 0
	for scanner.Scan() {
		line++
		raw := strings.TrimSpace(scanner.Text())
		if raw == "" || strings.HasPrefix(raw, "#") {
			continue
		}
		raw = strings.TrimPrefix(raw, "export ")
		key, value, ok := strings.Cut(raw, "=")
		key, value = strings.TrimSpace(key), strings.TrimSpace(value)
		if !ok || key == "" {
			return nil, fmt.Errorf("环境文件第 %d 行格式错误", line)
		}
		for i, r := range key {
			if !(r == '_' || r >= 'A' && r <= 'Z' || r >= 'a' && r <= 'z' || i > 0 && r >= '0' && r <= '9') {
				return nil, fmt.Errorf("环境文件第 %d 行变量名错误", line)
			}
		}
		if strings.HasPrefix(value, "\"") || strings.HasPrefix(value, "'") {
			if len(value) < 2 || value[len(value)-1] != value[0] {
				return nil, fmt.Errorf("环境文件第 %d 行引号不完整", line)
			}
			value = value[1 : len(value)-1]
		} else if i := strings.Index(value, " #"); i >= 0 {
			value = strings.TrimSpace(value[:i])
		}
		// Literal dotenv values only; never execute or expand their contents.
		values[key] = value
	}
	return values, scanner.Err()
}

func readEnv(file string) (map[string]string, error) {
	b, err := os.ReadFile(file)
	if os.IsNotExist(err) {
		return map[string]string{}, nil
	}
	if err != nil {
		return nil, fmt.Errorf("无法读取环境文件 %s", filepath.Base(file))
	}
	return parseEnv(string(b))
}

func inheritedEnv() map[string]string {
	env := map[string]string{}
	for _, entry := range os.Environ() {
		k, v, ok := strings.Cut(entry, "=")
		if ok {
			env[k] = v
		}
	}
	return env
}

func merge(dst, src map[string]string) {
	for k, v := range src {
		dst[k] = v
	}
}
func defaultValue(env map[string]string, key, value string) {
	if env[key] == "" {
		env[key] = value
	}
}

// Pass only the platform development credential to Vite's server environment.
// Derive it in the client so a running older supervisor can restart Web safely.
func localBrowserToken(root, profile string, inherited map[string]string) (string, error) {
	env := map[string]string{}
	for _, name := range []string{".env", ".env.dev", ".env.dev.platform"} {
		values, err := readEnv(filepath.Join(root, name))
		if err != nil {
			return "", err
		}
		merge(env, values)
	}
	merge(env, inherited)
	web, err := readEnv(filepath.Join(root, ".env.dev.web"))
	if err != nil {
		return "", err
	}
	// Web settings select the session mode, not the backend identity.
	for _, key := range []string{"OPSWEAVE_WEB_LOCAL_SESSION", "VITE_PLATFORM_AUTH"} {
		if value, ok := inherited[key]; ok {
			web[key] = value
		} else if _, ok := web[key]; !ok {
			web[key] = env[key]
		}
	}
	if web["OPSWEAVE_WEB_LOCAL_SESSION"] == "false" || web["VITE_PLATFORM_AUTH"] == "oidc" {
		return "", nil
	}
	if profile == "demo" {
		platform, err := makeSpec(root, "platform", profile, inherited)
		if err != nil {
			return "", err
		}
		env = platform.Env
	}
	if env["OPSWEAVE_AUTH_MODE"] != "dev" {
		return "", nil
	}
	token := env["OPSWEAVE_DEV_TOKEN"]
	if len(token) < 32 || len(token) > 4096 || strings.HasPrefix(token, "REPLACE_") || strings.ContainsAny(token, " \t\r\n\x00") {
		return "", fmt.Errorf("本地自动会话缺少有效服务端开发凭据，请配置 .env.dev.platform")
	}
	return token, nil
}

func makeSpec(root, id, profile string, inherited map[string]string) (spec, error) {
	env := map[string]string{}
	for _, name := range []string{".env", ".env.dev", ".env.dev." + id} {
		values, err := readEnv(filepath.Join(root, name))
		if err != nil {
			return spec{}, err
		}
		merge(env, values)
	}
	merge(env, inherited)
	if profile == "demo" {
		file := filepath.Join(root, ".tmp", "dev", "demo.env")
		if err := os.MkdirAll(filepath.Dir(file), 0700); err != nil {
			return spec{}, err
		}
		f, err := os.OpenFile(file, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
		if err == nil {
			_, err = fmt.Fprintln(f, "OPSWEAVE_DEV_TOKEN="+randomToken())
			f.Close()
		}
		if err != nil && !os.IsExist(err) {
			return spec{}, err
		}
		credentials, err := readEnv(file)
		if err != nil {
			return spec{}, err
		}
		if len(credentials["OPSWEAVE_DEV_TOKEN"]) < 32 {
			return spec{}, fmt.Errorf("演示凭据无效，请检查 .tmp/dev/demo.env")
		}
		merge(env, map[string]string{
			"OPSWEAVE_AUTH_MODE": "dev", "OPSWEAVE_DEV_TOKEN": credentials["OPSWEAVE_DEV_TOKEN"],
			"OPSWEAVE_DEV_TENANT": "tenant-demo", "OPSWEAVE_DEV_SUBJECT": "user-demo",
			"OPSWEAVE_DEV_PERMISSIONS": "entity.read,metric.read,source.sync", "OPSWEAVE_DEV_ENTITY_IDS": "",
			"OPSWEAVE_INVENTORY_STORE": "memory", "OPSWEAVE_ZABBIX_MODE": "fixture",
			"OPSWEAVE_MODE": "demo", "OPSWEAVE_PROVIDER": "mock", "OPSWEAVE_ALLOW_MODEL_EGRESS": "false",
			"OPSWEAVE_HISTORY_ENABLED": "false", "OPSWEAVE_VICTORIAMETRICS_URL": "", "OPSWEAVE_RUNTIME_URL": "", "OPSWEAVE_RUNTIME_KEY": "",
		})
		delete(env, "OPENAI_API_KEY")
	}
	if id != "runtime" {
		delete(env, "OPENAI_API_KEY")
	}
	if id != "web" {
		delete(env, "OPSWEAVE_WEB_LOCAL_TOKEN")
	}
	if (id == "platform" && env["OPSWEAVE_AUTH_MODE"] == "dev") || (id == "runtime" && env["OPSWEAVE_MODE"] == "demo") {
		token := env["OPSWEAVE_DEV_TOKEN"]
		if len(token) < 32 || strings.HasPrefix(token, "REPLACE_") || strings.ContainsAny(token, " \t\r\n") {
			return spec{}, fmt.Errorf("开发 Token 未正确配置：请使用本机随机凭据，或显式使用 --demo 自动生成")
		}
	}
	java := "java"
	if env["JAVA_HOME"] != "" {
		java = filepath.Join(env["JAVA_HOME"], "bin", "java")
		if runtime.GOOS == "windows" {
			java += ".exe"
		}
	}
	s := spec{ID: id, Env: env, Profile: profile}
	switch id {
	case "platform", "worker":
		app, port := "platform-api", 8080
		if id == "worker" {
			app, port = "ingestion-worker", 8081
		}
		s.Label, s.Port, s.Health, s.Readiness = app, port, "/actuator/health/liveness", "/actuator/health/readiness"
		if id == "platform" {
			defaultValue(env, "OPSWEAVE_INVENTORY_STORE", "postgres")
			if env["OPSWEAVE_INVENTORY_STORE"] == "postgres" && env["OPSWEAVE_JDBC_URL"] == "" {
				return s, fmt.Errorf("platform 缺少 OPSWEAVE_JDBC_URL：配置 .env.dev 或显式使用 --demo")
			}
			s.Mode = valueOr(env["OPSWEAVE_AUTH_MODE"], "closed") + "/" + env["OPSWEAVE_INVENTORY_STORE"] + "/" + valueOr(env["OPSWEAVE_ZABBIX_MODE"], "closed")
		} else {
			defaultValue(env, "OPSWEAVE_HISTORY_SCHEMA_MODE", "verify")
			s.Mode = "history=" + valueOr(env["OPSWEAVE_HISTORY_ENABLED"], "false")
		}
		s.Build = &command{java, []string{"-classpath", filepath.Join(root, "gradle/wrapper/gradle-wrapper.jar"), "org.gradle.wrapper.GradleWrapperMain", ":apps:" + app + ":bootJar", "--no-daemon", "--console=plain"}, root}
		s.Run = command{java, []string{"-jar", filepath.Join(root, "apps", app, "build/libs", app+"-0.1.0-SNAPSHOT.jar"), "--server.address=127.0.0.1", fmt.Sprintf("--server.port=%d", port)}, root}
	case "runtime":
		s.Label, s.Port, s.Health, s.Readiness = "agent-runtime", 8090, "/healthz", "/readyz"
		env["OPSWEAVE_LISTEN"] = "127.0.0.1:8090"
		s.Mode = valueOr(env["OPSWEAVE_MODE"], "closed") + "/" + valueOr(env["OPSWEAVE_PROVIDER"], "mock")
		args := []string{"build", "-p", "opsweave-agent-runtime", "--locked"}
		if env["OPSWEAVE_PROVIDER"] == "rig-openai" {
			args = append(args, "--features", "rig-provider")
		}
		s.Build = &command{"cargo", args, root}
		bin := filepath.Join(root, "target/debug/opsweave-agent-runtime")
		if runtime.GOOS == "windows" {
			bin += ".exe"
		}
		s.Run = command{bin, nil, root}
	case "web":
		s.Label, s.Port, s.Health, s.Readiness, s.Mode = "web-console", 5173, "/", "/", "vite dev"
		cwd := filepath.Join(root, "apps/web-console")
		vite := filepath.Join(cwd, "node_modules/vite/bin/vite.js")
		if _, err := os.Stat(vite); err != nil {
			return s, fmt.Errorf("缺少 Web 依赖，请先运行 pnpm install --frozen-lockfile")
		}
		s.Run = command{"node", []string{vite, "--host", "127.0.0.1", "--port", "5173", "--strictPort"}, cwd}
	default:
		return s, fmt.Errorf("未知组件 %q；支持 platform/runtime/worker/web/all", id)
	}
	return s, nil
}

func valueOr(value, fallback string) string {
	if value == "" {
		return fallback
	}
	return value
}

func targets(target string) ([]string, error) {
	if target == "" || target == "all" {
		return serviceOrder, nil
	}
	for _, id := range serviceOrder {
		if id == target {
			return []string{id}, nil
		}
	}
	return nil, fmt.Errorf("未知组件 %q；支持 platform/runtime/worker/web/all", target)
}
