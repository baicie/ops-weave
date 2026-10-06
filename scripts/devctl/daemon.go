package main

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"
)

const protocol = "opsweave-dev-v1"

type managerState struct {
	PID   int    `json:"pid"`
	Port  int    `json:"port"`
	Token string `json:"token"`
}
type request struct {
	Action, Target, Profile string
	Env                     map[string]string
}
type row struct {
	ID, Label, State, Mode, StartedAt, Error string
	PID, Port, Health, Readiness             int
}
type response struct {
	Protocol, Root, Error string
	Rows                  []row
}
type service struct {
	spec     spec
	row      row
	ctx      context.Context
	cancel   context.CancelFunc
	done     chan struct{}
	doneOnce sync.Once
	process  *ownedProcess
}

func (s *service) complete() { s.doneOnce.Do(func() { close(s.done) }) }

type daemon struct {
	root, token string
	mu          sync.Mutex
	buildMu     sync.Mutex
	services    map[string]*service
	quit        chan struct{}
	quitOnce    sync.Once
	closing     bool
}

func stateFile(root string) string   { return filepath.Join(root, ".tmp/dev/manager.json") }
func logFile(root, id string) string { return filepath.Join(root, ".tmp/dev", id+".log") }

func portBusy(port int) bool {
	listener, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		return true
	}
	listener.Close()
	return false
}

func probe(port int, endpoint string) int {
	client := http.Client{Timeout: 700 * time.Millisecond, Transport: &http.Transport{Proxy: nil}, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	defer client.CloseIdleConnections()
	reply, err := client.Get(fmt.Sprintf("http://127.0.0.1:%d%s", port, endpoint))
	if err != nil {
		return 0
	}
	reply.Body.Close()
	return reply.StatusCode
}

func (d *daemon) snapshot() []row {
	d.mu.Lock()
	result := make([]row, 0, 4)
	for _, id := range serviceOrder {
		if s := d.services[id]; s != nil {
			result = append(result, s.row)
		} else {
			result = append(result, defaultRow(id))
		}
	}
	d.mu.Unlock()
	var wg sync.WaitGroup
	for i := range result {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			r := &result[i]
			health, ready := endpoints(r.ID)
			r.Health = probe(r.Port, health)
			r.Readiness = probe(r.Port, ready)
			// A queued/starting snapshot may precede our child binding the port.
			// Do not relabel an owned startup as an external process in that window.
			active := r.State == "queued" || r.State == "building" || r.State == "starting" || r.State == "running" || r.State == "stopping"
			if r.PID == 0 && !active && portBusy(r.Port) {
				r.State = "unmanaged"
			}
		}(i)
	}
	wg.Wait()
	return result
}

func defaultRow(id string) row {
	ports := map[string]int{"platform": 8080, "worker": 8081, "runtime": 8090, "web": 5173}
	return row{ID: id, Label: id, State: "stopped", Mode: "—", Port: ports[id]}
}
func endpoints(id string) (string, string) {
	if id == "platform" || id == "worker" {
		return "/actuator/health/liveness", "/actuator/health/readiness"
	}
	if id == "runtime" {
		return "/healthz", "/readyz"
	}
	return "/", "/"
}

func (d *daemon) start(req request) error {
	if req.Profile != "local" && req.Profile != "demo" {
		return fmt.Errorf("启动模式必须为 local 或 demo")
	}
	ids, err := targets(req.Target)
	if err != nil {
		return err
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.closing {
		return fmt.Errorf("管理进程正在停止，请稍后重试")
	}
	var jobs []*service
	// Preflight the entire selection before spawning any component.
	for _, id := range ids {
		if old := d.services[id]; old != nil && (old.row.State == "building" || old.row.State == "starting" || old.row.State == "running" || old.row.State == "queued" || old.row.State == "stopping") {
			if old.spec.Profile != req.Profile {
				return fmt.Errorf("%s 已按 %s 模式启动；切换模式请显式使用 restart %s", id, old.spec.Profile, id)
			}
			continue
		}
		definition, err := makeSpec(d.root, id, req.Profile, req.Env)
		if err != nil {
			return err
		}
		if portBusy(definition.Port) {
			return fmt.Errorf("%s 端口 %d 已占用；不会接管或停止非本工具启动的进程", id, definition.Port)
		}
		ctx, cancel := context.WithCancel(context.Background())
		s := &service{spec: definition, row: row{ID: id, Label: definition.Label, Port: definition.Port, State: "queued", Mode: definition.Mode}, ctx: ctx, cancel: cancel, done: make(chan struct{})}
		// Keep contexts attached to their queued job without storing credentials in state files.
		jobs = append(jobs, s)
	}
	for _, s := range jobs {
		d.services[s.spec.ID] = s
	}
	// Only one build in this batch runs at a time, avoiding competing Java/Rust builds.
	go func() {
		for _, s := range jobs {
			d.runService(s)
		}
	}()
	return nil
}

func (d *daemon) setState(s *service, state, reason string) {
	d.mu.Lock()
	s.row.State, s.row.Error = state, reason
	d.mu.Unlock()
}

func (d *daemon) runService(s *service) {
	// A queued service can be stopped before its build begins.
	if s.ctx.Err() != nil {
		d.setState(s, "stopped", "")
		s.complete()
		return
	}
	log, err := newLog(logFile(d.root, s.spec.ID), s.spec.Env)
	if err != nil {
		d.setState(s, "failed", "无法写入组件日志")
		s.complete()
		return
	}
	// Run exits are observed in a separate goroutine, so the next component can start.
	finish := func(state, reason string) {
		if s.ctx.Err() != nil {
			state, reason = "stopped", ""
		}
		d.mu.Lock()
		s.row.State, s.row.Error = state, reason
		if s.process == nil || s.process.exited() {
			s.process = nil
			s.row.PID = 0
		}
		d.mu.Unlock()
		if reason != "" {
			fmt.Fprintln(log, reason)
		}
		log.Close()
		s.complete()
	}
	spawn := func(c command, state string) (*ownedProcess, error) {
		d.mu.Lock()
		defer d.mu.Unlock()
		if s.ctx.Err() != nil {
			return nil, fmt.Errorf("启动已取消")
		}
		s.row.State = state
		fmt.Fprintf(log, "\n[%s] %s %s\n", time.Now().Format(time.RFC3339), s.spec.ID, state)
		p, err := launch(c, s.spec.Env, log)
		if err != nil {
			return nil, err
		}
		s.process = p
		s.row.PID = p.cmd.Process.Pid
		return p, nil
	}
	if s.spec.Build != nil {
		// Share the build limit across requests from every terminal.
		d.buildMu.Lock()
		defer d.buildMu.Unlock()
		if s.ctx.Err() != nil {
			finish("stopped", "")
			return
		}
		p, err := spawn(*s.spec.Build, "building")
		if err != nil {
			finish("failed", err.Error())
			return
		}
		select {
		case <-p.done:
		case <-time.After(5 * time.Minute):
			if err := stopTree(p); err != nil {
				finish("failed", err.Error())
				return
			}
			finish("failed", "构建超过 5 分钟，已停止；查看组件日志后手动重试")
			return
		}
		d.mu.Lock()
		cancelled := s.row.State == "stopping"
		s.process = nil
		s.row.PID = 0
		d.mu.Unlock()
		if cancelled {
			finish("stopped", "")
			return
		}
		if p.err != nil {
			finish("failed", "构建失败，请查看组件日志；没有重试或 Mock 回退")
			return
		}
	}
	if portBusy(s.spec.Port) {
		finish("failed", "启动时端口被其他进程占用")
		return
	}
	p, err := spawn(s.spec.Run, "starting")
	if err != nil {
		finish("failed", err.Error())
		return
	}
	go func() {
		defer log.Close()
		defer s.complete()
		deadline := time.Now().Add(90 * time.Second)
		for !p.exited() {
			d.mu.Lock()
			state := s.row.State
			d.mu.Unlock()
			if state == "stopping" {
				break
			}
			if probe(s.spec.Port, s.spec.Health) == 200 {
				d.mu.Lock()
				if s.row.State != "stopping" {
					s.row.State = "running"
					s.row.StartedAt = time.Now().Format(time.RFC3339)
				}
				d.mu.Unlock()
				break
			}
			if time.Now().After(deadline) {
				if err := stopTree(p); err != nil {
					d.setState(s, "failed", err.Error())
					return
				}
				d.mu.Lock()
				s.process = nil
				s.row.PID = 0
				s.row.State = "failed"
				s.row.Error = "存活探针 90 秒内未成功，已停止服务；请查看日志"
				d.mu.Unlock()
				return
			}
			time.Sleep(200 * time.Millisecond)
		}
		<-p.done
		d.mu.Lock()
		s.process = nil
		s.row.PID = 0
		if s.row.State == "stopping" {
			s.row.State = "stopped"
			s.row.Error = ""
		} else {
			s.row.State = "failed"
			s.row.Error = "服务已退出；没有自动重启"
		}
		d.mu.Unlock()
	}()
}

func (d *daemon) stop(target string) error {
	ids, err := targets(target)
	if err != nil {
		return err
	}
	for _, id := range ids {
		d.mu.Lock()
		s := d.services[id]
		if s == nil || s.row.State == "stopped" || (s.row.State == "failed" && s.process == nil) {
			d.mu.Unlock()
			continue
		}
		queued := s.row.State == "queued"
		s.row.State = "stopping"
		s.cancel()
		p := s.process
		d.mu.Unlock()
		if queued {
			d.setState(s, "stopped", "")
			s.complete()
			continue
		}
		if p != nil {
			if err := stopTree(p); err != nil {
				return err
			}
		}
		select {
		case <-s.done:
		case <-time.After(20 * time.Second):
			return fmt.Errorf("%s 停止超时", id)
		}
	}
	return nil
}

func (d *daemon) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	if r.Method != "POST" || r.URL.Path != "/control" {
		http.Error(w, "not found", 404)
		return
	}
	if subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), []byte("Bearer "+d.token)) != 1 {
		http.Error(w, "unauthorized", 401)
		return
	}
	var req request
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20))
	decoder.DisallowUnknownFields()
	if decoder.Decode(&req) != nil {
		http.Error(w, "invalid request", 400)
		return
	}
	var err error
	switch req.Action {
	case "status":
	case "start":
		err = d.start(req)
	case "stop":
		err = d.stop(req.Target)
	case "shutdown":
		d.mu.Lock()
		d.closing = true
		d.mu.Unlock()
		err = d.stop("all")
	default:
		err = fmt.Errorf("未知管理动作")
	}
	reply := response{Protocol: protocol, Root: d.root}
	if err != nil {
		reply.Error = err.Error()
	} else {
		reply.Rows = d.snapshot()
	}
	json.NewEncoder(w).Encode(reply)
	if req.Action == "shutdown" && err == nil {
		go func() { time.Sleep(100 * time.Millisecond); d.quitOnce.Do(func() { close(d.quit) }) }()
	}
}

func runDaemon(root string) error {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return err
	}
	d := &daemon{root: root, token: randomToken(), services: map[string]*service{}, quit: make(chan struct{})}
	state := managerState{PID: os.Getpid(), Port: listener.Addr().(*net.TCPAddr).Port, Token: d.token}
	b, _ := json.Marshal(state)
	temp := stateFile(root) + ".new"
	if err := os.WriteFile(temp, b, 0600); err != nil {
		listener.Close()
		return err
	}
	if err := os.Rename(temp, stateFile(root)); err != nil {
		listener.Close()
		return err
	}
	server := &http.Server{Handler: d, ReadHeaderTimeout: 2 * time.Second, ReadTimeout: 5 * time.Second, WriteTimeout: 30 * time.Second, IdleTimeout: 10 * time.Second}
	go server.Serve(listener)
	<-d.quit
	server.Close()
	// No persisted PID is used for killing. Orphaned ports are reported as unmanaged.
	current, _ := readState(root)
	if current.Token == d.token {
		os.Remove(stateFile(root))
	}
	return nil
}
