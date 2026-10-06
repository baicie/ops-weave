package main

import (
	"bufio"
	"bytes"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

func readState(root string) (managerState, error) {
	var state managerState
	b, err := os.ReadFile(stateFile(root))
	if err != nil {
		return state, err
	}
	err = json.Unmarshal(b, &state)
	if err == nil && (state.PID <= 0 || state.Port <= 0 || state.Port > 65535 || len(state.Token) < 32) {
		err = fmt.Errorf("管理状态文件无效")
	}
	return state, err
}

func call(root string, state managerState, req request) (response, error) {
	var reply response
	b, _ := json.Marshal(req)
	r, _ := http.NewRequest("POST", fmt.Sprintf("http://127.0.0.1:%d/control", state.Port), bytes.NewReader(b))
	r.Header.Set("Authorization", "Bearer "+state.Token)
	client := http.Client{Timeout: 30 * time.Second, Transport: &http.Transport{Proxy: nil}, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	defer client.CloseIdleConnections()
	res, err := client.Do(r)
	if err != nil {
		return reply, fmt.Errorf("管理进程不可达；不会根据旧 PID 停止其他进程")
	}
	defer res.Body.Close()
	if res.StatusCode != 200 {
		return reply, fmt.Errorf("管理进程身份验证失败（HTTP %d）", res.StatusCode)
	}
	if json.NewDecoder(io.LimitReader(res.Body, 1<<20)).Decode(&reply) != nil || reply.Protocol != protocol || reply.Root != root {
		return reply, fmt.Errorf("管理进程身份不匹配")
	}
	if reply.Error != "" {
		return reply, fmt.Errorf("%s", reply.Error)
	}
	return reply, nil
}

func ensureDaemon(root string) (managerState, error) {
	if state, err := readState(root); err == nil {
		if _, err := call(root, state, request{Action: "status"}); err == nil {
			return state, nil
		}
		if processAlive(state.PID) {
			return state, fmt.Errorf("已有管理进程仍存活但不可达，请检查 .tmp/dev；不会接管未知进程")
		}
		os.Remove(stateFile(root))
	} else if !os.IsNotExist(err) {
		return managerState{}, err
	}
	dir := filepath.Join(root, ".tmp/dev")
	if err := os.MkdirAll(dir, 0700); err != nil {
		return managerState{}, err
	}
	lock := filepath.Join(dir, "daemon.lock")
	for i := 0; i < 100; i++ {
		f, err := os.OpenFile(lock, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
		if err == nil {
			fmt.Fprintln(f, os.Getpid())
			f.Close()
			defer os.Remove(lock)
			// Recheck after acquiring the lock; another CLI may have just finished startup.
			if state, err := readState(root); err == nil {
				if _, err := call(root, state, request{Action: "status"}); err == nil {
					return state, nil
				}
			}
			executable, err := os.Executable()
			if err != nil {
				return managerState{}, err
			}
			// Copy the executable out of go run's temporary directory. This also lets
			// Windows rebuild the CLI while its supervisor is still running.
			binary, err := os.ReadFile(executable)
			if err != nil {
				return managerState{}, err
			}
			digest := sha256.Sum256(binary)
			supervisor := filepath.Join(dir, fmt.Sprintf("supervisor-%x%s", digest[:8], filepath.Ext(executable)))
			f, err = os.OpenFile(supervisor, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0700)
			if err == nil {
				_, err = f.Write(binary)
				f.Close()
			}
			if err != nil && !os.IsExist(err) {
				return managerState{}, err
			}
			cmd := exec.Command(supervisor, "_daemon", root)
			cmd.Dir = root
			configureDaemon(cmd)
			if err := cmd.Start(); err != nil {
				return managerState{}, fmt.Errorf("无法启动管理进程")
			}
			cmd.Process.Release()
			for j := 0; j < 100; j++ {
				time.Sleep(50 * time.Millisecond)
				if state, err := readState(root); err == nil {
					if _, err := call(root, state, request{Action: "status"}); err == nil {
						return state, nil
					}
				}
			}
			return managerState{}, fmt.Errorf("管理进程启动超时")
		}
		if !os.IsExist(err) {
			return managerState{}, err
		}
		b, _ := os.ReadFile(lock)
		pid, _ := strconv.Atoi(strings.TrimSpace(string(b)))
		if pid > 0 && !processAlive(pid) {
			os.Remove(lock)
		}
		time.Sleep(50 * time.Millisecond)
		if state, err := readState(root); err == nil {
			if _, err := call(root, state, request{Action: "status"}); err == nil {
				return state, nil
			}
		}
	}
	return managerState{}, fmt.Errorf("另一个启动器正在初始化，请稍后重试")
}

func findRoot() (string, error) {
	cwd, _ := os.Getwd()
	exe, _ := os.Executable()
	for _, start := range []string{cwd, filepath.Dir(exe)} {
		for dir := start; ; dir = filepath.Dir(dir) {
			if _, err := os.Stat(filepath.Join(dir, "settings.gradle.kts")); err == nil {
				if _, err := os.Stat(filepath.Join(dir, "package.json")); err == nil {
					return filepath.Abs(dir)
				}
			}
			if filepath.Dir(dir) == dir {
				break
			}
		}
	}
	return "", fmt.Errorf("请在 OpsWeave 仓库内运行启动器")
}

func printRows(rows []row) {
	fmt.Printf("\n%-10s %-11s %-7s %-12s %s\n", "组件", "状态", "PID", "存活/就绪", "模式 / 地址")
	for _, r := range rows {
		code := func(n int) string {
			if n == 0 {
				return "—"
			}
			return strconv.Itoa(n)
		}
		fmt.Printf("%-10s %-11s %-7d %-12s %s  http://127.0.0.1:%d\n", r.ID, r.State, r.PID, code(r.Health)+"/"+code(r.Readiness), r.Mode, r.Port)
		if r.Error != "" {
			fmt.Println("  " + r.Error)
		}
	}
	fmt.Println("unmanaged = 端口由其他进程占用；本工具只停止自己启动的组件。就绪 503 表示当前不能提供业务服务。")
}

func status(root string) ([]row, error) {
	if state, err := readState(root); err == nil {
		reply, err := call(root, state, request{Action: "status"})
		if err == nil {
			return reply.Rows, nil
		}
		if processAlive(state.PID) {
			return nil, err
		}
	} else if !os.IsNotExist(err) {
		return nil, err
	}
	d := daemon{root: root, services: map[string]*service{}}
	return d.snapshot(), nil
}

func logs(root, id string) error {
	if id == "" || id == "all" {
		return fmt.Errorf("日志需要指定 platform/runtime/worker/web")
	}
	if _, err := targets(id); err != nil {
		return err
	}
	f, err := os.Open(logFile(root, id))
	if os.IsNotExist(err) {
		fmt.Println("尚无组件日志。")
		return nil
	}
	if err != nil {
		return err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return err
	}
	offset := info.Size() - 256*1024
	if offset < 0 {
		offset = 0
	}
	f.Seek(offset, io.SeekStart)
	b, err := io.ReadAll(f)
	if err != nil {
		return err
	}
	text := string(b)
	if offset > 0 {
		_, text, _ = strings.Cut(text, "\n")
	}
	lines := strings.Split(strings.TrimRight(text, "\n"), "\n")
	if len(lines) > 80 {
		lines = lines[len(lines)-80:]
	}
	fmt.Println(strings.Join(lines, "\n"))
	return nil
}

func execute(root, action, target, profile string) error {
	if action == "status" {
		rows, err := status(root)
		if err == nil {
			printRows(rows)
		}
		return err
	}
	if action == "logs" {
		return logs(root, target)
	}
	if action != "start" && action != "stop" && action != "restart" {
		return fmt.Errorf("未知动作 %q", action)
	}
	ids, err := targets(target)
	if err != nil {
		return err
	}
	env := inheritedEnv()
	delete(env, "OPSWEAVE_WEB_LOCAL_TOKEN")
	if action != "stop" {
		for _, id := range ids {
			if id == "web" {
				token, err := localBrowserToken(root, profile, env)
				if err != nil {
					return err
				}
				env["OPSWEAVE_WEB_LOCAL_TOKEN"] = token
			}
		}
	}
	if action == "stop" || action == "restart" {
		state, err := readState(root)
		if err == nil {
			stopAction := "stop"
			if action == "stop" && (target == "all" || target == "") {
				stopAction = "shutdown"
			}
			reply, err := call(root, state, request{Action: stopAction, Target: target})
			if err != nil {
				return err
			}
			if action == "stop" {
				printRows(reply.Rows)
				if stopAction == "shutdown" {
					for i := 0; i < 100; i++ {
						if _, err := os.Stat(stateFile(root)); os.IsNotExist(err) {
							return nil
						}
						time.Sleep(50 * time.Millisecond)
					}
					return fmt.Errorf("服务已停止，但管理进程退出尚未完成")
				}
				return nil
			}
		} else if !os.IsNotExist(err) {
			return err
		}
		if action == "stop" {
			rows, err := status(root)
			if err == nil {
				printRows(rows)
			}
			return err
		}
	}
	state, err := ensureDaemon(root)
	if err != nil {
		return err
	}
	reply, err := call(root, state, request{Action: "start", Target: target, Profile: profile, Env: env})
	if err != nil {
		return err
	}
	printRows(reply.Rows)
	deadline := time.Now().Add(21 * time.Minute)
	lastPrint := time.Now()
	for {
		complete, failed := true, false
		for _, r := range reply.Rows {
			for _, id := range ids {
				if r.ID == id {
					if r.State == "failed" || r.State == "unmanaged" {
						failed = true
					} else if r.State != "running" && r.State != "stopped" {
						complete = false
					}
				}
			}
		}
		if complete {
			printRows(reply.Rows)
			if failed {
				return fmt.Errorf("部分组件启动失败；使用 logs <组件> 查看日志")
			}
			return nil
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("等待启动超时，服务状态请用 status 查看")
		}
		time.Sleep(500 * time.Millisecond)
		reply, err = call(root, state, request{Action: "status"})
		if err != nil {
			return err
		}
		if time.Since(lastPrint) > 15*time.Second {
			printRows(reply.Rows)
			lastPrint = time.Now()
		}
	}
}

func menu(root, profile string) error {
	reader := bufio.NewReader(os.Stdin)
	for {
		fmt.Printf("\nOpsWeave 开发服务管理 · %s\n1 启动全部   2 停止全部   3 重启全部\n4 查看状态   5 管理单个组件   6 查看日志\n0 退出菜单（服务继续运行）\n选择：", profile)
		choice, err := reader.ReadString('\n')
		if err != nil {
			return nil
		}
		choice = strings.TrimSpace(choice)
		action, target := "", "all"
		switch choice {
		case "0":
			return nil
		case "1":
			action = "start"
		case "2":
			action = "stop"
		case "3":
			action = "restart"
		case "4":
			action = "status"
		case "5", "6":
			fmt.Print("组件（platform/runtime/worker/web）：")
			target, err = reader.ReadString('\n')
			if err != nil {
				return nil
			}
			target = strings.TrimSpace(target)
			if choice == "6" {
				action = "logs"
			} else {
				fmt.Print("动作（start/stop/restart）：")
				action, err = reader.ReadString('\n')
				if err != nil {
					return nil
				}
				action = strings.TrimSpace(action)
			}
		default:
			fmt.Println("请输入菜单编号。")
			continue
		}
		if err := execute(root, action, target, profile); err != nil {
			fmt.Fprintln(os.Stderr, "错误：", err)
		}
	}
}

func main() {
	if len(os.Args) == 3 && os.Args[1] == "_daemon" {
		if err := runDaemon(os.Args[2]); err != nil {
			os.Exit(1)
		}
		return
	}
	profile := "local"
	args := []string{}
	for _, arg := range os.Args[1:] {
		if arg == "--demo" {
			profile = "demo"
		} else if arg == "--help" || arg == "-h" {
			fmt.Println("OpsWeave 开发服务管理\n用法：opsweave-dev [start|stop|restart|status|logs] [all|platform|runtime|worker|web] [--demo]\n已编译管理器运行无需 Go；源码构建需要 Go 1.23+。\n无参数打开交互菜单。默认读取 .env、.env.dev、.env.dev.<组件>，系统环境变量优先。\n--demo 显式使用内存/Fixture/Mock，Worker 采集关闭，Token 位于 .tmp/dev/demo.env。\n状态和日志位于 .tmp/dev；stop all 同时关闭管理进程。")
			return
		} else {
			args = append(args, arg)
		}
	}
	root, err := findRoot()
	if err == nil {
		if len(args) == 0 {
			err = menu(root, profile)
		} else if len(args) <= 2 {
			target := "all"
			if len(args) == 2 {
				target = args[1]
			}
			err = execute(root, args[0], target, profile)
		} else {
			err = fmt.Errorf("参数过多，请使用 --help")
		}
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "错误：", err)
		os.Exit(1)
	}
}
