package main

import (
	"fmt"
	"os"
	"os/exec"
	"regexp"
	"sort"
	"strings"
	"sync"
)

var authPattern = regexp.MustCompile(`(?i)(Authorization\s*[:=]\s*(?:Bearer\s+)?)[^\s,;]+`)
var secretKey = regexp.MustCompile(`(?i)TOKEN|PASSWORD|SECRET|API_KEY|RUNTIME_KEY`)

type safeLog struct {
	mu       sync.Mutex
	file     *os.File
	secrets  []string
	pending  []byte
	dropping bool
}

func newLog(file string, env map[string]string) (*safeLog, error) {
	f, err := os.OpenFile(file, os.O_WRONLY|os.O_CREATE|os.O_APPEND, 0600)
	if err != nil {
		return nil, err
	}
	l := &safeLog{file: f}
	for k, v := range env {
		if secretKey.MatchString(k) && v != "" {
			l.secrets = append(l.secrets, v)
		}
	}
	sort.Slice(l.secrets, func(i, j int) bool { return len(l.secrets[i]) > len(l.secrets[j]) })
	return l, nil
}

func (l *safeLog) redact(text string) string {
	for _, secret := range l.secrets {
		text = strings.ReplaceAll(text, secret, "[REDACTED]")
	}
	return authPattern.ReplaceAllString(text, "${1}[REDACTED]")
}

func (l *safeLog) Write(b []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	// Buffer full lines so credentials split across writes remain redacted.
	for _, c := range b {
		if c == '\n' {
			if l.dropping {
				l.dropping = false
			} else if _, err := l.file.WriteString(l.redact(string(l.pending)) + "\n"); err != nil {
				return 0, err
			}
			l.pending = nil
		} else if !l.dropping {
			l.pending = append(l.pending, c)
			if len(l.pending) > 65536 {
				l.pending = nil
				l.dropping = true
				if _, err := l.file.WriteString("[超长日志行已省略]\n"); err != nil {
					return 0, err
				}
			}
		}
	}
	return len(b), nil
}

func (l *safeLog) Close() error {
	l.mu.Lock()
	defer l.mu.Unlock()
	if !l.dropping {
		if _, err := l.file.WriteString(l.redact(string(l.pending))); err != nil {
			l.file.Close()
			return err
		}
	}
	return l.file.Close()
}

type ownedProcess struct {
	cmd    *exec.Cmd
	done   chan struct{}
	err    error
	stopMu sync.Mutex
}

func stopTree(p *ownedProcess) error {
	p.stopMu.Lock()
	defer p.stopMu.Unlock()
	if p.exited() {
		return nil
	}
	return stopTreeOS(p)
}

func launch(c command, env map[string]string, log *safeLog) (*ownedProcess, error) {
	cmd := exec.Command(c.Bin, c.Args...)
	cmd.Dir = c.Dir
	for k, v := range env {
		cmd.Env = append(cmd.Env, k+"="+v)
	}
	cmd.Stdout, cmd.Stderr = log, log
	configureChild(cmd)
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("无法启动 %s，请检查工具链和日志", filepathBase(c.Bin))
	}
	p := &ownedProcess{cmd: cmd, done: make(chan struct{})}
	go func() { p.err = cmd.Wait(); close(p.done) }()
	return p, nil
}

func filepathBase(value string) string {
	parts := strings.FieldsFunc(value, func(r rune) bool { return r == '/' || r == '\\' })
	if len(parts) == 0 {
		return value
	}
	return parts[len(parts)-1]
}
func (p *ownedProcess) exited() bool {
	select {
	case <-p.done:
		return true
	default:
		return false
	}
}
