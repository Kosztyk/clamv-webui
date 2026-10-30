package cli

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http/httputil"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/modelcontextprotocol/go-sdk/mcp"
)

// scripts/test-clamctl-integration.py supplies an isolated real Spring application.
func TestSpringApplication(t *testing.T) {
	backend := os.Getenv("CLAMCTL_INTEGRATION_SERVER")
	if backend == "" {
		t.Skip("run scripts/test-clamctl-integration.py for the real Spring/native CLI check")
	}
	upstream, err := url.Parse(backend)
	if err != nil || upstream.Scheme != "http" || !oneOf(upstream.Hostname(), "127.0.0.1", "localhost") {
		t.Fatal("integration backend must be local")
	}
	proxy := &httputil.ReverseProxy{Rewrite: func(request *httputil.ProxyRequest) {
		request.SetURL(upstream)
		for _, key := range []string{"Forwarded", "X-Forwarded-Proto", "X-Forwarded-Host", "X-Forwarded-Port", "X-Forwarded-For"} {
			request.Out.Header.Del(key)
		}
		request.Out.Header.Set("X-Forwarded-Proto", "https")
	}}
	h := testCLI(t, proxy.ServeHTTP)
	binary := os.Getenv("CLAMCTL_INTEGRATION_BINARY")
	if binary == "" {
		t.Fatal("CLAMCTL_INTEGRATION_BINARY is required")
	}
	password := os.Getenv("CLAMCTL_INTEGRATION_PASSWORD")
	run := func(expected int, input string, args ...string) []byte {
		t.Helper()
		ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
		defer cancel()
		flags := append([]string{"--config", h.config, "--server", h.server.URL, "--ca-file", h.ca, "--json"}, args...)
		command := exec.CommandContext(ctx, binary, flags...)
		for _, entry := range os.Environ() {
			name, _, _ := strings.Cut(entry, "=")
			if !strings.HasPrefix(name, "CLAMAV_") {
				command.Env = append(command.Env, entry)
			}
		}
		command.Stdin = strings.NewReader(input)
		var output, stderr bytes.Buffer
		command.Stdout = &output
		command.Stderr = &stderr
		err := command.Run()
		actual := 0
		if err != nil {
			if exit, ok := err.(*exec.ExitError); ok {
				actual = exit.ExitCode()
			} else {
				t.Fatal(err)
			}
		}
		if actual != expected {
			t.Fatalf("command %s: exit %d expected %d; %s", args[0], actual, expected, stderr.String())
		}
		return output.Bytes()
	}
	run(0, "", "config", "set-server", h.server.URL)
	run(0, password+"\n", "login", "--username", "admin", "--password-stdin")
	run(0, "", "status")
	var values []endpoint
	if err := json.Unmarshal(run(0, "", "endpoints", "list"), &values); err != nil || len(values) != 1 {
		t.Fatal("endpoint list mismatch", err)
	}
	dir := t.TempDir()
	clean := filepath.Join(dir, "clean.txt")
	infected := filepath.Join(dir, "eicar.txt")
	if err := os.WriteFile(clean, []byte("clean scan validation"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(infected, []byte("synthetic EICAR fixture"), 0600); err != nil {
		t.Fatal(err)
	}
	run(0, "", "scan", "upload", clean, "--wait", "--interval=20ms")
	var detected job
	if err := json.Unmarshal(run(3, "", "scan", "upload", infected, "--wait", "--interval=20ms"), &detected); err != nil {
		t.Fatal(err)
	}
	if detected.Verdict != "VIRUS_FOUND" || len(detected.Signatures) != 1 || detected.DurationMillis == nil {
		t.Fatal("full pipeline lost detection or duration")
	}
	run(3, "", "jobs", "get", detected.ID)
	run(3, "", "jobs", "watch", detected.ID, "--interval=20ms")
	run(0, "", "jobs", "list", "--status=FINISHED")
	run(0, "", "audit", "list", "--limit=20")
	client, err := newClient(h.server.URL, credential{Username: "admin", Password: password}, h.ca, time.Minute, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer client.close()
	body, _ := json.Marshal(map[string]any{"label": "native CLI integration", "scopes": []string{"health:read", "endpoints:read", "scan:upload", "scan:path", "jobs:read", "audit:read"}, "endpointIds": []int64{values[0].ID}, "pathRoots": []string{filepath.Dir(os.Getenv("CLAMCTL_INTEGRATION_SCAN_PATH"))}})
	var issued struct {
		Secret string `json:"secret"`
	}
	if err := client.request(context.Background(), "POST", "/tokens", nil, bytes.NewReader(body), "application/json", &issued, false); err != nil {
		t.Fatal(err)
	}
	if issued.Secret == "" {
		t.Fatal("API token was not issued")
	}
	run(0, issued.Secret+"\n", "login", "--token-stdin")
	run(0, "", "scan", "path", os.Getenv("CLAMCTL_INTEGRATION_SCAN_PATH"), "--wait", "--interval=20ms")
	run(0, "", "status")
	testSpringMCP(t, h, binary, password, issued.Secret, clean, infected, os.Getenv("CLAMCTL_INTEGRATION_SCAN_PATH"))
	run(0, "", "logout")
	t.Log("Native Linux executable passed Basic/CSRF and scoped Bearer scans against the real Spring API; ClamD was a synthetic wire fixture.")
}

func testSpringMCP(t *testing.T, h *harness, binary, password, token, clean, infected, serverPath string) {
	t.Helper()
	failed := filepath.Join(filepath.Dir(clean), "scanner-error.txt")
	if err := os.WriteFile(failed, []byte("CLAMD_ERROR synthetic fixture"), 0600); err != nil {
		t.Fatal(err)
	}
	for _, mode := range []string{"basic", "token"} {
		t.Run("MCP_"+mode, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
			defer cancel()
			command := exec.CommandContext(ctx, binary, "--config", h.config, "--server", h.server.URL, "--ca-file", h.ca,
				"mcp", "serve", "--upload-root", filepath.Dir(clean), "--interval=20ms", "--timeout=30s")
			for _, entry := range os.Environ() {
				name, _, _ := strings.Cut(entry, "=")
				if !strings.HasPrefix(name, "CLAMAV_") {
					command.Env = append(command.Env, entry)
				}
			}
			if mode == "token" {
				command.Env = append(command.Env, "CLAMAV_API_TOKEN="+token)
			} else {
				command.Env = append(command.Env, "CLAMAV_USERNAME=admin", "CLAMAV_PASSWORD="+password)
			}
			var stderr bytes.Buffer
			command.Stderr = &stderr
			client := mcp.NewClient(&mcp.Implementation{Name: "spring-mcp-integration", Version: "1"}, nil)
			session, err := client.Connect(ctx, &mcp.CommandTransport{Command: command}, &mcp.ClientSessionOptions{ProtocolVersion: "2025-11-25"})
			if err != nil {
				t.Fatal("native MCP initialize", err, stderr.String())
			}
			defer func() {
				if err := session.Close(); err != nil {
					t.Error("native MCP shutdown", err, stderr.String())
				}
			}()
			tools, err := session.ListTools(ctx, nil)
			if err != nil || len(tools.Tools) != 5 {
				t.Fatal("native MCP discovery", err)
			}
			resources, err := session.ListResources(ctx, nil)
			if err != nil || len(resources.Resources) != 3 {
				t.Fatal("native resources", err)
			}
			for _, test := range []struct{ path, verdict string }{{clean, "OK"}, {infected, "VIRUS_FOUND"}, {failed, "ERROR"}} {
				value := mcpDecode[job](t, mcpCall(t, session, "clamav_scan_file", map[string]any{"file_path": test.path}, false))
				if value.Verdict != test.verdict || value.DurationMillis == nil || value.QuarantineStatus == "" {
					t.Fatal("native MCP scan result", value)
				}
				if test.verdict == "VIRUS_FOUND" && len(value.Signatures) != 1 {
					t.Fatal("native MCP signature missing")
				}
				if test.verdict == "ERROR" && (value.Error == nil || value.Error.Code != "SCAN_FAILED") {
					t.Fatal("native MCP safe error missing")
				}
				mcpDecode[job](t, mcpCall(t, session, "clamav_get_job", map[string]any{"job_id": value.ID}, false))
			}
			path := mcpDecode[mcpPathResult](t, mcpCall(t, session, "clamav_scan_path", map[string]any{"path": serverPath}, false))
			if path.Status != "ACCEPTED" {
				t.Fatal("path not accepted")
			}
			health := mcpDecode[mcpHealthResult](t, mcpCall(t, session, "clamav_check_health", map[string]any{}, false))
			if health.Health.Connectivity != "UP" || health.Health.DatabaseTimestamp == nil || health.Metrics.Threads["live"] != 5 || health.Metrics.Queue["items"] != 0 {
				t.Fatal("native MCP health", health)
			}
			endpoints := mcpDecode[mcpEndpointsResult](t, mcpCall(t, session, "clamav_list_endpoints", map[string]any{}, false))
			if len(endpoints.Endpoints) != 1 {
				t.Fatal("native endpoints")
			}
			for _, resource := range resources.Resources {
				result, err := session.ReadResource(ctx, &mcp.ReadResourceParams{URI: resource.URI})
				if err != nil || len(result.Contents) != 1 || !json.Valid([]byte(result.Contents[0].Text)) {
					t.Fatal("native MCP resource", resource.URI, err)
				}
			}
			mcpCall(t, session, "clamav_scan_path", map[string]any{"path": "/outside/allowed/roots"}, true)
			mcpCall(t, session, "clamav_scan_file", map[string]any{"file_path": serverPath}, true)
			t.Log("Native stdio MCP passed all five tools and three resources with", mode, "authentication against Spring and synthetic ClamD.")
			if mode == "token" {
				check := exec.CommandContext(ctx, "python3", filepath.Join("..", "..", "..", "scripts", "home-lab-mcp-test.py"), "--clamctl", binary, "--upload-root", filepath.Dir(clean), "--eicar-file", infected, "--server-path", serverPath)
				check.Env = append(command.Env, "CLAMAV_SERVER="+h.server.URL, "CLAMAV_CA_FILE="+h.ca, "CLAMAV_CONFIG="+h.config)
				if output, err := check.CombinedOutput(); err != nil {
					t.Fatalf("home-lab MCP checker: %v\n%s", err, output)
				} else {
					t.Log(string(output))
				}
			}
		})
	}
}
