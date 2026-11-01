package cli

import (
	"bytes"
	"context"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

type harness struct {
	t          *testing.T
	server     *httptest.Server
	config, ca string
	env        map[string]string
}
type runResult struct {
	code        int
	out, stderr string
}

func testCLI(t *testing.T, handler http.HandlerFunc) *harness {
	t.Helper()
	server := httptest.NewTLSServer(handler)
	t.Cleanup(server.Close)
	dir := t.TempDir()
	ca := filepath.Join(dir, "ca.pem")
	if err := os.WriteFile(ca, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: server.Certificate().Raw}), 0600); err != nil {
		t.Fatal(err)
	}
	return &harness{t, server, filepath.Join(dir, "config.json"), ca, map[string]string{"CLAMAV_API_TOKEN": "synthetic-api-token"}}
}

func (h *harness) run(args ...string) runResult { return h.input(context.Background(), "", args...) }
func (h *harness) input(ctx context.Context, input string, args ...string) runResult {
	h.t.Helper()
	var out, stderr bytes.Buffer
	app := New(strings.NewReader(input), &out, &stderr, "2.1.0")
	app.Getenv = func(key string) string { return h.env[key] }
	all := append([]string{"--config", h.config, "--server", h.server.URL, "--ca-file", h.ca}, args...)
	code := app.Run(ctx, all)
	return runResult{code, out.String(), stderr.String()}
}

func writeJSON(w http.ResponseWriter, value any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(value)
}
func check(t *testing.T, got runResult, code int, contains ...string) {
	t.Helper()
	if got.code != code {
		t.Fatalf("exit %d, expected %d; stderr=%s stdout=%s", got.code, code, got.stderr, got.out)
	}
	for _, text := range contains {
		if !strings.Contains(got.out, text) {
			t.Errorf("output missing %q: %s", text, got.out)
		}
	}
}

func TestConfigLoginAndCredentialLifecycle(t *testing.T) {
	var calls atomic.Int64
	h := testCLI(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.URL.Path != "/api/v1/auth" {
			t.Error("unexpected route", r.URL.Path)
		}
		if r.Header.Get("Authorization") != "Bearer synthetic-api-token" {
			t.Error("missing token header")
		}
		writeJSON(w, map[string]any{"authenticated": true, "principal": "token:1"})
	})
	check(t, h.run("config", "set-server", h.server.URL+"/api/v1/"), 0, h.server.URL)
	check(t, h.run("login", "--json"), 0, "authenticated", "token:1")
	value, err := loadConfig(h.config)
	if err != nil {
		t.Fatal(err)
	}
	if value.Auth.Token != "synthetic-api-token" || value.CAFile != h.ca {
		t.Fatal("credential or CA path not persisted")
	}
	info, _ := os.Stat(h.config)
	if runtime.GOOS != "windows" && info.Mode().Perm() != 0600 {
		t.Errorf("configuration permissions %v", info.Mode())
	}
	shown := h.run("config", "show", "--json")
	check(t, shown, 0, "credentialStored")
	if strings.Contains(shown.out, "synthetic-api-token") {
		t.Fatal("config show exposed token")
	}
	check(t, h.run("config", "set-server", "https://different.example"), 0)
	changed, _ := loadConfig(h.config)
	if changed.Auth.mode() != "none" {
		t.Fatal("server change retained credentials")
	}
	if calls.Load() != 1 {
		t.Fatal("configuration commands made network requests")
	}
	check(t, h.run("logout", "--json"), 0, "loggedOut")
}

func TestTokenAliasesAndLoginFailureNeverExposeOrPersistSecrets(t *testing.T) {
	for _, name := range []string{"CLAMAV_API_TOKEN", "CLAMAV_API_KEY"} {
		t.Run(name, func(t *testing.T) {
			h := testCLI(t, func(w http.ResponseWriter, r *http.Request) {
				if r.Header.Get("Authorization") != "Bearer private-token" {
					t.Error("token alias not used")
				}
				w.WriteHeader(401)
				writeJSON(w, map[string]string{"error": "private-token", "message": "private-token private-password"})
			})
			h.env = map[string]string{name: "private-token"}
			result := h.run("login", "--json")
			check(t, result, 1)
			if strings.Contains(result.out+result.stderr, "private-token") || strings.Contains(result.stderr, "private-password") {
				t.Fatal("secret echoed")
			}
			if _, err := os.Stat(h.config); !os.IsNotExist(err) {
				t.Fatal("failed login persisted credentials")
			}
			var failure map[string]any
			if json.Unmarshal([]byte(result.stderr), &failure) != nil {
				t.Fatal("JSON errors are not machine readable")
			}
		})
	}
}

func TestBasicLoginPreservesFlagLikePasswordAndPostsWithCsrfCookie(t *testing.T) {
	var submissions atomic.Int64
	h := testCLI(t, func(w http.ResponseWriter, r *http.Request) {
		username, password, ok := r.BasicAuth()
		if !ok || username != "admin" || password != "--json" {
			t.Error("Basic credentials were altered")
		}
		switch r.URL.Path {
		case "/api/v1/auth":
			writeJSON(w, map[string]any{"authenticated": true, "principal": "admin"})
		case "/api/v1/csrf":
			http.SetCookie(w, &http.Cookie{Name: "JSESSIONID", Value: "synthetic-session", Path: "/", Secure: true, HttpOnly: true})
			writeJSON(w, map[string]string{"token": "synthetic-csrf", "headerName": "X-CSRF-TOKEN"})
		case "/api/v1/scans/paths":
			cookie, err := r.Cookie("JSESSIONID")
			if err != nil || cookie.Value != "synthetic-session" || r.Header.Get("X-CSRF-TOKEN") != "synthetic-csrf" {
				t.Error("CSRF cookie/header missing")
			}
			var body map[string]any
			_ = json.NewDecoder(r.Body).Decode(&body)
			if body["path"] != "/var/www/uploads" || body["endpointId"] != float64(7) {
				t.Error("server path or endpoint altered", body)
			}
			submissions.Add(1)
			w.WriteHeader(202)
			writeJSON(w, map[string]string{"jobId": "basic-path-job"})
		default:
			t.Error("unexpected route", r.URL.Path)
			w.WriteHeader(404)
		}
	})
	h.env = map[string]string{}
	check(t, h.run("login", "--username", "admin", "--password", "--json"), 0, "admin")
	check(t, h.run("scan", "path", "/var/www/uploads", "--endpoint", "7", "--json"), 0, "basic-path-job")
	if submissions.Load() != 1 {
		t.Fatal("path submission missing")
	}
}

func TestTokenStdinAndPasswordStdin(t *testing.T) {
	for _, mode := range []string{"token", "password"} {
		t.Run(mode, func(t *testing.T) {
			h := testCLI(t, func(w http.ResponseWriter, r *http.Request) {
				if mode == "token" {
					if r.Header.Get("Authorization") != "Bearer input-secret" {
						t.Error("stdin token incorrect")
					}
				} else {
					u, p, _ := r.BasicAuth()
					if u != "admin" || p != "input-secret" {
						t.Error("stdin password incorrect")
					}
				}
				writeJSON(w, map[string]any{"authenticated": true, "principal": "test"})
			})
			h.env = map[string]string{}
			args := []string{"login", "--" + mode + "-stdin", "--json"}
			if mode == "password" {
				args = append(args, "--username", "admin")
			}
			result := h.input(context.Background(), "input-secret\r\n", args...)
			check(t, result, 0)
			if strings.Contains(result.out+result.stderr, "input-secret") {
				t.Fatal("stdin secret printed")
			}
			check(t, h.input(context.Background(), "", args...), 2)
		})
	}
}

func TestStatusTablesJsonAndUnhealthyExit(t *testing.T) {
	for _, up := range []bool{true, false} {
		t.Run(fmt.Sprint(up), func(t *testing.T) {
			h := testCLI(t, func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Query().Get("endpointId") != "5" {
					t.Error("endpoint filter missing")
				}
				if strings.HasSuffix(r.URL.Path, "/health/clamd") {
					state := "UP"
					if !up {
						state = "DOWN"
						w.WriteHeader(503)
					}
					writeJSON(w, map[string]any{"endpointId": 5, "endpoint": "default", "connectivity": state, "version": "ClamAV 1.5.4", "signatureDatabaseFreshness": "FRESH"})
				} else {
					writeJSON(w, map[string]any{"endpointId": 5, "ok": up, "pools": 1, "threads": map[string]int{"live": 5, "idle": 3, "busy": 2, "max": 10}, "queue": map[string]int{"items": 0}, "memory": map[string]string{"pools_used": "1.500M", "pools_total": "2.000M"}})
				}
			})
			code := 0
			if !up {
				code = 1
			}
			check(t, h.run("status", "--endpoint", "5"), code, "ClamAV 1.5.4", "Threads busy", "Pool memory used", "1.500M")
			result := h.run("--json", "status", "--endpoint=5")
			check(t, result, code)
			var report map[string]any
			if json.Unmarshal([]byte(result.out), &report) != nil || report["metrics"] == nil {
				t.Fatal("invalid status JSON")
			}
		})
	}
}

func TestListCommandsAndFilters(t *testing.T) {
	h := testCLI(t, func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v1/endpoints":
			writeJSON(w, []map[string]any{{"id": 1, "name": "default", "enabled": true}})
		case "/api/v1/jobs":
			q := r.URL.Query()
			if q.Get("status") != "RUNNING" || q.Get("size") != "10" || q.Get("page") != "2" || q.Get("endpointId") != "1" {
				t.Error("job filters incorrect", q)
			}
			writeJSON(w, map[string]any{"items": []map[string]string{{"id": "job-1", "status": "RUNNING", "type": "UPLOAD"}}, "page": 2, "size": 10, "totalItems": 30, "totalPages": 3})
		case "/api/v1/audit":
			if r.URL.Query().Get("size") != "20" {
				t.Error("audit limit incorrect")
			}
			writeJSON(w, map[string]any{"items": []map[string]any{{"id": 1, "at": "2026-10-30T00:00:00Z", "actor": "admin", "action": "SCAN_RESULT", "outcome": "OK"}}})
		default:
			t.Error("unexpected route", r.URL.Path)
			w.WriteHeader(404)
		}
	})
	check(t, h.run("endpoints", "list"), 0, "default", "true")
	check(t, h.run("jobs", "list", "--status", "running", "--limit", "10", "--page", "2", "--endpoint", "1", "--json"), 0, "job-1", "totalPages")
	check(t, h.run("audit", "list", "--limit", "20"), 0, "SCAN_RESULT", "admin")
}

func TestMultipartUploadWaitAndFinalVerdictExitCodes(t *testing.T) {
	for _, verdict := range []string{"OK", "VIRUS_FOUND", "ERROR"} {
		t.Run(verdict, func(t *testing.T) {
			var polls atomic.Int64
			payload := bytes.Repeat([]byte("test-payload"), 10000)
			h := testCLI(t, func(w http.ResponseWriter, r *http.Request) {
				switch r.URL.Path {
				case "/api/v1/scans/uploads":
					if r.Header.Get("Authorization") != "Bearer synthetic-api-token" {
						t.Error("token upload unauthenticated")
					}
					if r.Header.Get("X-CSRF-TOKEN") != "" {
						t.Error("Bearer upload should not fetch CSRF")
					}
					reader, err := r.MultipartReader()
					if err != nil {
						t.Error(err)
						w.WriteHeader(400)
						return
					}
					files := 0
					for {
						part, err := reader.NextPart()
						if err == io.EOF {
							break
						}
						if err != nil {
							t.Error(err)
							break
						}
						data, _ := io.ReadAll(part)
						if part.FormName() == "endpointId" {
							if string(data) != "9" {
								t.Error("multipart endpoint missing")
							}
						} else {
							if part.FormName() != "files" || part.FileName() != "payload.bin" || !bytes.Equal(data, payload) {
								t.Error("multipart file corrupted")
							}
							files++
						}
					}
					if files != 1 {
						t.Error("incorrect multipart file count")
					}
					w.WriteHeader(202)
					writeJSON(w, map[string]any{"created": 1, "jobIds": []string{"scan-job"}})
				case "/api/v1/jobs/scan-job/progress":
					state := "RUNNING"
					done := polls.Add(1) > 1
					if done {
						state = "FINISHED"
					}
					writeJSON(w, map[string]any{"jobId": "scan-job", "status": state, "completed": done})
				case "/api/v1/jobs/scan-job":
					writeJSON(w, map[string]any{"id": "scan-job", "type": "UPLOAD", "status": "FINISHED", "verdict": verdict, "signatures": []string{"Test.EICAR"}, "durationMillis": 1500})
				default:
					t.Error("unexpected route", r.URL.Path)
					w.WriteHeader(404)
				}
			})
			path := filepath.Join(t.TempDir(), "payload.bin")
			if err := os.WriteFile(path, payload, 0600); err != nil {
				t.Fatal(err)
			}
			code := 0
			if verdict == "VIRUS_FOUND" {
				code = 3
			} else if verdict == "ERROR" {
				code = 1
			}
			result := h.run("scan", "upload", path, "--wait", "--interval=1ms", "--json", "--endpoint", "9")
			check(t, result, code, verdict, "Test.EICAR", "1500")
			decoder := json.NewDecoder(strings.NewReader(result.out))
			var value any
			if decoder.Decode(&value) != nil || decoder.Decode(&value) != io.EOF {
				t.Fatal("scan --wait --json must emit one JSON document")
			}
			if result.stderr != "" {
				t.Errorf("JSON wait printed progress: %s", result.stderr)
			}
		})
	}
}

func TestJobsWatchEmitsOnlyChangedProgressAndFinalDetails(t *testing.T) {
	var polls atomic.Int64
	h := testCLI(t, func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/progress") {
			count := polls.Add(1)
			state := "QUEUED"
			if count == 3 {
				state = "RUNNING"
			}
			if count >= 4 {
				state = "FINISHED"
			}
			writeJSON(w, map[string]any{"jobId": "watch-job", "status": state, "completed": count >= 4})
		} else {
			writeJSON(w, map[string]any{"id": "watch-job", "status": "FINISHED", "verdict": "VIRUS_FOUND", "signatures": []string{"Test.Virus"}})
		}
	})
	result := h.run("jobs", "watch", "watch-job", "--interval", "1ms", "--json")
	check(t, result, 3)
	decoder := json.NewDecoder(strings.NewReader(result.out))
	events := []string{}
	for {
		var event map[string]any
		err := decoder.Decode(&event)
		if err == io.EOF {
			break
		}
		if err != nil {
			t.Fatal(err)
		}
		events = append(events, event["event"].(string))
	}
	if strings.Join(events, ",") != "progress,progress,progress,result" {
		t.Fatal("unexpected watch events", events)
	}
}

func TestWaitTimeoutAndCancellation(t *testing.T) {
	h := testCLI(t, func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, map[string]any{"jobId": "pending-job", "status": "QUEUED", "completed": false})
	})
	result := h.run("jobs", "watch", "pending-job", "--interval=2ms", "--timeout=15ms", "--json")
	check(t, result, 4)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	check(t, h.input(ctx, "", "jobs", "watch", "pending-job", "--json"), 130)
}

func TestRedirectsNeverForwardCredentialsOrReplayScanSubmissions(t *testing.T) {
	var destination atomic.Int64
	other := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { destination.Add(1) }))
	defer other.Close()
	h := testCLI(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Location", other.URL+"/stolen")
		w.WriteHeader(307)
	})
	check(t, h.run("scan", "path", "/scandir/test", "--json"), 1)
	if destination.Load() != 0 {
		t.Fatal("redirect was followed")
	}
}

func TestInputValidationAndSavedCredentialServerBinding(t *testing.T) {
	var calls atomic.Int64
	h := testCLI(t, func(w http.ResponseWriter, r *http.Request) { calls.Add(1); writeJSON(w, map[string]any{}) })
	for _, args := range [][]string{
		{"jobs", "get", "../private"}, {"jobs", "list", "--status", "BOGUS"}, {"audit", "list", "--limit", "101"},
		{"jobs", "watch", "id", "--timeout=0"}, {"status", "--endpoint=0"}, {"login", "--token", ""},
		{"scan", "upload", "/missing/file"}, {"config", "set-server", "http://example.test"},
		{"config", "set-server", "https://user:password@example.test"}, {"config", "set-server", "https://example.test?token=private"},
	} {
		result := h.run(args...)
		if result.code != 2 {
			t.Errorf("bad input returned %d for %v", result.code, args)
		}
	}
	if calls.Load() != 0 {
		t.Fatal("invalid input reached server")
	}
	if err := saveConfig(h.config, config{Version: 1, Server: "https://previous.example", Auth: credential{Token: "saved-secret"}}); err != nil {
		t.Fatal(err)
	}
	h.env = map[string]string{}
	check(t, h.run("status"), 2)
	if calls.Load() != 0 {
		t.Fatal("saved credentials forwarded to a different server")
	}
}

func TestHttpFailuresMalformedJsonAndUntrustedCertificates(t *testing.T) {
	for _, status := range []int{401, 403, 404, 429, 500} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			h := testCLI(t, func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(status)
				writeJSON(w, map[string]string{"error": "forbidden"})
			})
			check(t, h.run("endpoints", "list", "--json"), 1)
		})
	}
	h := testCLI(t, func(w http.ResponseWriter, r *http.Request) { fmt.Fprint(w, "<html>private body</html>") })
	result := h.run("endpoints", "list", "--json")
	check(t, result, 1)
	if strings.Contains(result.stderr, "private body") {
		t.Fatal("invalid response body echoed")
	}
	var out, stderr bytes.Buffer
	app := New(strings.NewReader(""), &out, &stderr, "2.1.0")
	app.Getenv = func(key string) string { return h.env[key] }
	if code := app.Run(context.Background(), []string{"--config", h.config, "--server", h.server.URL, "endpoints", "list"}); code != 1 {
		t.Fatal("untrusted TLS certificate accepted")
	}
}

func TestHelpVersionAndTerminalOutputSafety(t *testing.T) {
	for _, args := range [][]string{{"--help"}, {"scan", "upload", "--help"}, {"version", "--json"}} {
		var out, stderr bytes.Buffer
		app := New(strings.NewReader(""), &out, &stderr, "2.1.0")
		app.Getenv = func(string) string { return "" }
		if app.Run(context.Background(), args) != 0 {
			t.Fatal("help/version requires configuration")
		}
	}
	if strings.ContainsAny(safeCell("name\x1b[31m\nnext\tcell\u202e"), "\x1b\n\t\u202e") {
		t.Fatal("terminal controls not removed")
	}
}

func TestRequestTimeoutAndSafeResponseLimits(t *testing.T) {
	h := testCLI(t, func(w http.ResponseWriter, r *http.Request) { <-r.Context().Done() })
	check(t, h.run("endpoints", "list", "--request-timeout=20ms", "--json"), 4)
	large := testCLI(t, func(w http.ResponseWriter, r *http.Request) { fmt.Fprint(w, strings.Repeat("x", maxResponse+1)) })
	check(t, large.run("endpoints", "list", "--json"), 1)
	// Check the caller's cancellation also interrupts an active HTTP request.
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	check(t, h.input(ctx, "", "endpoints", "list", "--json"), 4)
}

func TestCorruptConfigAndPosixCredentialPermissionsAreRejected(t *testing.T) {
	h := testCLI(t, func(w http.ResponseWriter, r *http.Request) { t.Error("invalid configuration reached server") })
	if err := os.WriteFile(h.config, []byte("{broken"), 0600); err != nil {
		t.Fatal(err)
	}
	check(t, h.run("status"), 2)
	if err := saveConfig(h.config, config{Version: 1, Server: h.server.URL, Auth: credential{Token: "secret"}}); err != nil {
		t.Fatal(err)
	}
	if runtime.GOOS != "windows" {
		if err := os.Chmod(h.config, 0644); err != nil {
			t.Fatal(err)
		}
		check(t, h.run("status"), 2)
	}
}

func TestCredentialInputIsCancellable(t *testing.T) {
	reader, writer := io.Pipe()
	defer reader.Close()
	defer writer.Close()
	ctx, cancel := context.WithCancel(context.Background())
	finished := make(chan error, 1)
	go func() { _, err := readCredential(ctx, reader); finished <- err }()
	cancel()
	select {
	case err := <-finished:
		if err != context.Canceled {
			t.Fatal("credential cancellation lost", err)
		}
	case <-time.After(time.Second):
		t.Fatal("credential input ignored cancellation")
	}
}
