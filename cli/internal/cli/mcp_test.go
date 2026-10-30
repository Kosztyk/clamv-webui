package cli

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/modelcontextprotocol/go-sdk/mcp"
)

func testMCPBridge(t *testing.T, handler http.HandlerFunc, flags ...string) (*mcpBridge, string) {
	t.Helper()
	h := testCLI(t, handler)
	client, err := newClient(h.server.URL, credential{Token: "synthetic-api-token"}, h.ca, time.Second, nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(client.close)
	root, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	options, err := parseMCPOptions(append([]string{"--upload-root", root, "--interval=1ms", "--timeout=1s"}, flags...))
	if err != nil {
		t.Fatal(err)
	}
	b, err := newMCPBridge(client, 7, options)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(b.close)
	return b, root
}

func mcpSession(t *testing.T, server *mcp.Server) *mcp.ClientSession {
	t.Helper()
	ctx := context.Background()
	left, right := mcp.NewInMemoryTransports()
	ss, err := server.Connect(ctx, left, nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ss.Close() })
	client := mcp.NewClient(&mcp.Implementation{Name: "clamav-integration-client", Version: "1"}, nil)
	session, err := client.Connect(ctx, right, &mcp.ClientSessionOptions{ProtocolVersion: "2025-11-25"})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { session.Close() })
	return session
}

func mcpCall(t *testing.T, session *mcp.ClientSession, name string, args any, wantError bool) *mcp.CallToolResult {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	result, err := session.CallTool(ctx, &mcp.CallToolParams{Name: name, Arguments: args})
	if err != nil {
		t.Fatal(name, err)
	}
	if result.IsError != wantError {
		t.Fatalf("%s isError=%v expected %v: %+v", name, result.IsError, wantError, result.Content)
	}
	return result
}

func mcpDecode[T any](t *testing.T, result *mcp.CallToolResult) T {
	t.Helper()
	var value T
	data, err := json.Marshal(result.StructuredContent)
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(data, &value); err != nil {
		t.Fatal(err)
	}
	if len(result.Content) != 1 {
		t.Fatal("missing compatibility text content")
	}
	var text any
	if err := json.Unmarshal([]byte(result.Content[0].(*mcp.TextContent).Text), &text); err != nil {
		t.Fatal("text output is not JSON", err)
	}
	return value
}

func TestMCPDiscoveryToolsAndResources(t *testing.T) {
	var uploads atomic.Int64
	b, root := testMCPBridge(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer synthetic-api-token" {
			t.Error("lost upstream identity")
		}
		switch r.URL.Path {
		case "/api/v1/scans/uploads":
			if err := r.ParseMultipartForm(1024); err != nil {
				t.Error(err)
			}
			if r.FormValue("endpointId") != "9" {
				t.Error("endpoint override missing")
			}
			file, _, err := r.FormFile("files")
			if err != nil {
				t.Error(err)
				return
			}
			defer file.Close()
			data, _ := io.ReadAll(file)
			if string(data) != "synthetic sample" {
				t.Error("stream data changed")
			}
			uploads.Add(1)
			w.WriteHeader(202)
			writeJSON(w, submitted{1, []string{"scan-1"}})
		case "/api/v1/scans/paths":
			var args map[string]any
			_ = json.NewDecoder(r.Body).Decode(&args)
			if args["path"] != "/scandir/sample" || args["endpointId"] != float64(7) {
				t.Error("server path changed", args)
			}
			w.WriteHeader(202)
			writeJSON(w, map[string]string{"jobId": "path-1"})
		case "/api/v1/jobs/scan-1":
			millis := int64(42)
			writeJSON(w, job{ID: "scan-1", Status: "FINISHED", Verdict: "VIRUS_FOUND", Signatures: []string{"Test.Signature"}, DurationMillis: &millis, QuarantineStatus: "RECORDED"})
		case "/api/v1/health/clamd":
			if r.URL.Query().Get("endpointId") != "7" {
				t.Error("default endpoint missing")
			}
			w.WriteHeader(503)
			writeJSON(w, health{Connectivity: "UP", Freshness: "STALE", DatabaseVersion: "27920", DatabaseTime: "Fri Oct 30 10:00:00 2026"})
		case "/api/v1/metrics/clamd":
			writeJSON(w, metrics{OK: true, Threads: map[string]int64{"live": 5, "idle": 3, "busy": 2}, Queue: map[string]int64{"items": 2}, Memory: map[string]string{"pools_used": "1.500M"}})
		case "/api/v1/endpoints":
			writeJSON(w, []endpoint{{7, "Primary", true}, {9, "Other", false}})
		case "/api/v1/jobs":
			if r.URL.Query().Get("size") != "20" || r.URL.Query().Get("page") != "0" || r.URL.Query().Get("endpointId") != "7" {
				t.Error("resource pagination/filter missing")
			}
			writeJSON(w, page[job]{Items: []job{{ID: "scan-1", Status: "FINISHED", Verdict: "VIRUS_FOUND"}}, Size: 20, TotalItems: 1, TotalPages: 1})
		case "/api/v1/audit":
			writeJSON(w, page[audit]{Items: []audit{{ID: 1, Actor: "token:1", Action: "SCAN_RESULT", Outcome: "VIRUS_FOUND"}}, Size: 20, TotalItems: 1, TotalPages: 1})
		default:
			t.Error("unexpected API route", r.URL.Path)
			http.NotFound(w, r)
		}
	})
	session := mcpSession(t, b.server("2.0.0"))
	tools, err := session.ListTools(context.Background(), nil)
	if err != nil || len(tools.Tools) != 5 {
		t.Fatal("tool discovery", err)
	}
	for _, tool := range tools.Tools {
		if tool.InputSchema == nil || tool.OutputSchema == nil {
			t.Error("missing schema", tool.Name)
		}
		isScan := strings.HasPrefix(tool.Name, "clamav_scan_")
		if tool.Annotations == nil || tool.Annotations.ReadOnlyHint == isScan {
			t.Error("incorrect tool annotations", tool.Name)
		}
		if isScan && (tool.Annotations.DestructiveHint == nil || !*tool.Annotations.DestructiveHint) {
			t.Error("quarantine risk not advertised")
		}
	}
	file := filepath.Join(root, "sample.bin")
	if err := os.WriteFile(file, []byte("synthetic sample"), 0600); err != nil {
		t.Fatal(err)
	}
	value := mcpDecode[job](t, mcpCall(t, session, "clamav_scan_file", map[string]any{"file_path": file, "endpoint_id": 9}, false))
	if value.Verdict != "VIRUS_FOUND" || len(value.Signatures) != 1 || value.DurationMillis == nil || value.QuarantineStatus != "RECORDED" || uploads.Load() != 1 {
		t.Fatal("scan result lost", value)
	}
	path := mcpDecode[mcpPathResult](t, mcpCall(t, session, "clamav_scan_path", map[string]any{"path": "/scandir/sample"}, false))
	if path.JobID != "path-1" || path.Status != "ACCEPTED" {
		t.Fatal("wrong accepted state", path)
	}
	mcpDecode[job](t, mcpCall(t, session, "clamav_get_job", map[string]any{"job_id": "scan-1"}, false))
	health := mcpDecode[mcpHealthResult](t, mcpCall(t, session, "clamav_check_health", map[string]any{}, false))
	if health.Health.Freshness != "STALE" || health.Metrics.Threads["busy"] != 2 {
		t.Fatal("health lost", health)
	}
	endpoints := mcpDecode[mcpEndpointsResult](t, mcpCall(t, session, "clamav_list_endpoints", map[string]any{}, false))
	if len(endpoints.Endpoints) != 2 {
		t.Fatal("endpoint list lost")
	}
	resources, err := session.ListResources(context.Background(), nil)
	if err != nil || len(resources.Resources) != 3 {
		t.Fatal("resource discovery", err)
	}
	for _, resource := range resources.Resources {
		result, err := session.ReadResource(context.Background(), &mcp.ReadResourceParams{URI: resource.URI})
		if err != nil || len(result.Contents) != 1 || result.Contents[0].URI != resource.URI || result.Contents[0].MIMEType != "application/json" || !json.Valid([]byte(result.Contents[0].Text)) {
			t.Fatal("resource invalid", resource.URI, err)
		}
	}
	if _, err := session.ReadResource(context.Background(), &mcp.ReadResourceParams{URI: "file:///private/secret"}); err == nil {
		t.Fatal("arbitrary resource allowed")
	}
}

func TestMCPRejectsInvalidArgumentsAndKeepsAPIAuthorization(t *testing.T) {
	var calls atomic.Int64
	b, _ := testMCPBridge(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.WriteHeader(403)
		writeJSON(w, map[string]string{"error": "forbidden", "message": "private server path"})
	})
	session := mcpSession(t, b.server("2.0.0"))
	for _, test := range []struct {
		name string
		args any
	}{
		{"clamav_scan_file", map[string]any{}},
		{"clamav_scan_file", map[string]any{"file_path": 42}},
		{"clamav_scan_file", map[string]any{"file_path": "relative.bin"}},
		{"clamav_scan_path", map[string]any{"path": "/scan", "endpoint_id": -1}},
		{"clamav_scan_path", map[string]any{"path": "/scan\nVERSION"}},
		{"clamav_get_job", map[string]any{"job_id": "../../tokens"}},
		{"clamav_check_health", map[string]any{"unexpected": "argument"}},
	} {
		mcpCall(t, session, test.name, test.args, true)
	}
	if calls.Load() != 0 {
		t.Fatal("invalid arguments reached API")
	}
	result := mcpCall(t, session, "clamav_scan_path", map[string]any{"path": "/outside/roots"}, true)
	text := result.Content[0].(*mcp.TextContent).Text
	if !strings.Contains(text, "403") || strings.Contains(text, "private") {
		t.Fatal("scope failure lost or reflected", text)
	}
	mcpCall(t, session, "clamav_get_job", map[string]any{"job_id": "restricted-job"}, true)
	if _, err := session.ReadResource(context.Background(), &mcp.ReadResourceParams{URI: "clamav://audit/recent"}); err == nil {
		t.Fatal("audit scope bypassed")
	}
}

func TestMCPLocalFileBoundaries(t *testing.T) {
	b, root := testMCPBridge(t, func(http.ResponseWriter, *http.Request) { t.Error("unexpected API request") }, "--max-upload-bytes=10")
	allowed := filepath.Join(root, "allowed.bin")
	empty := filepath.Join(root, "empty")
	large := filepath.Join(root, "large")
	for path, data := range map[string][]byte{allowed: []byte("sample"), empty: {}, large: []byte("too large by far")} {
		if err := os.WriteFile(path, data, 0600); err != nil {
			t.Fatal(err)
		}
	}
	file, err := b.openUpload(allowed)
	if err != nil {
		t.Fatal(err)
	}
	file.Close()
	outside := filepath.Join(t.TempDir(), "outside.bin")
	if err := os.WriteFile(outside, []byte("secret"), 0600); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{outside, empty, large, root, "relative", allowed + "\x00"} {
		if file, err := b.openUpload(path); err == nil {
			file.Close()
			t.Fatal("unauthorized upload", path)
		}
	}
	t.Run("symlink_escape", func(t *testing.T) {
		link := filepath.Join(root, "escape")
		if err := os.Symlink(outside, link); err != nil {
			t.Skip("OS denies creating test symlink")
		}
		if file, err := b.openUpload(link); err == nil {
			file.Close()
			t.Fatal("symlink escaped root")
		}
	})
	t.Run("default_deny", func(t *testing.T) {
		o := b.options
		o.Roots = nil
		denied, err := newMCPBridge(b.client, 0, o)
		if err != nil {
			t.Fatal(err)
		}
		defer denied.close()
		if file, err := denied.openUpload(allowed); err == nil {
			file.Close()
			t.Fatal("implicit local access")
		}
	})
}

func TestMCPWaitErrorsAndConcurrency(t *testing.T) {
	b, root := testMCPBridge(t, func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v1/scans/uploads":
			_, _ = io.Copy(io.Discard, r.Body)
			writeJSON(w, submitted{1, []string{"pending-1"}})
		case "/api/v1/jobs/pending-1":
			writeJSON(w, job{ID: "pending-1", Status: "RUNNING"})
		default:
			http.NotFound(w, r)
		}
	}, "--timeout=25ms")
	file := filepath.Join(root, "sample")
	_ = os.WriteFile(file, []byte("sample"), 0600)
	session := mcpSession(t, b.server("2.0.0"))
	result := mcpCall(t, session, "clamav_scan_file", map[string]any{"file_path": file}, true)
	if text := result.Content[0].(*mcp.TextContent).Text; !strings.Contains(text, "timed out") || !strings.Contains(text, "pending-1") {
		t.Fatal("cannot resume timed-out job", text)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, _, err := b.scanFile(ctx, nil, mcpFileArgs{FilePath: file}); err == nil || !strings.Contains(err.Error(), "cancelled") {
		t.Fatal("cancellation lost", err)
	}
	for i := 0; i < cap(b.slots); i++ {
		b.slots <- struct{}{}
	}
	mcpCall(t, session, "clamav_check_health", map[string]any{}, true)
	for i := 0; i < cap(b.slots); i++ {
		<-b.slots
	}
}

type mcpAuthTransport struct {
	base  http.RoundTripper
	token string
}

func (tr mcpAuthTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	copy := r.Clone(r.Context())
	copy.Header = r.Header.Clone()
	copy.Header.Set("Authorization", "Bearer "+tr.token)
	return tr.base.RoundTrip(copy)
}

func TestMCPStreamableHTTPAuthenticationOriginsAndProtocol(t *testing.T) {
	b, _ := testMCPBridge(t, func(w http.ResponseWriter, r *http.Request) { writeJSON(w, []endpoint{{7, "Primary", true}}) })
	token := strings.Repeat("m", 40)
	handler, err := mcpHTTPHandler(b.server("2.0.0"), token, []string{"https://assistant.example"})
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(handler)
	defer server.Close()
	for _, test := range []struct {
		token, origin, host string
		code                int
	}{
		{"", "", "", 401}, {"wrong", "", "", 401}, {token, "https://attacker.example", "", 403},
		{token, "null", "", 403}, {token, "", "attacker.example", 403},
	} {
		request, _ := http.NewRequest("POST", server.URL+"/mcp", strings.NewReader(`{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"raw","version":"1"}}}`))
		request.Header.Set("Content-Type", "application/json")
		request.Header.Set("Accept", "application/json, text/event-stream")
		if test.token != "" {
			request.Header.Set("Authorization", "Bearer "+test.token)
		}
		if test.origin != "" {
			request.Header.Set("Origin", test.origin)
		}
		if test.host != "" {
			request.Host = test.host
		}
		response, err := server.Client().Do(request)
		if err != nil {
			t.Fatal(err)
		}
		response.Body.Close()
		if response.StatusCode != test.code {
			t.Fatalf("HTTP protections: got %d expected %d", response.StatusCode, test.code)
		}
	}
	for _, version := range []string{"2025-11-25", "2026-07-28"} {
		t.Run(version, func(t *testing.T) {
			client := mcp.NewClient(&mcp.Implementation{Name: "http-test", Version: "1"}, nil)
			httpClient := &http.Client{Transport: mcpAuthTransport{server.Client().Transport, token}, Timeout: 5 * time.Second}
			session, err := client.Connect(context.Background(), &mcp.StreamableClientTransport{Endpoint: server.URL + "/mcp", HTTPClient: httpClient, DisableStandaloneSSE: true}, &mcp.ClientSessionOptions{ProtocolVersion: version})
			if err != nil {
				t.Fatal(err)
			}
			defer session.Close()
			value := mcpDecode[mcpEndpointsResult](t, mcpCall(t, session, "clamav_list_endpoints", map[string]any{}, false))
			if len(value.Endpoints) != 1 {
				t.Fatal("HTTP tool failed")
			}
		})
	}
	request, _ := http.NewRequest("POST", server.URL+"/mcp", strings.NewReader(strings.Repeat("x", maxMCPFrame+1)))
	request.Header.Set("Authorization", "Bearer "+token)
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Accept", "application/json, text/event-stream")
	response, err := server.Client().Do(request)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != 413 {
		t.Fatal("unbounded HTTP frame", response.StatusCode)
	}
	for _, origin := range []string{"*", "http://assistant.example", "https://assistant.example/path", "https://user:password@assistant.example"} {
		if _, err := mcpHTTPHandler(b.server("2.0.0"), token, []string{origin}); err == nil {
			t.Fatal("invalid Origin accepted", origin)
		}
	}
}

func TestMCPOptionsAndHTTPSecretValidation(t *testing.T) {
	for _, args := range [][]string{{"--transport=sse"}, {"--max-upload-bytes=0"}, {"--interval=0s"}, {"--timeout=-1s"}, {"--listen=0.0.0.0:8090"}, {"extra"}} {
		if _, err := parseMCPOptions(args); err == nil {
			t.Fatal("bad options", args)
		}
	}
	for _, address := range []string{"0.0.0.0:8090", "localhost:8090", "192.0.2.1:8090", ":8090", "127.0.0.1:99999"} {
		if err := validateMCPListen(address); err == nil {
			t.Fatal("unsafe listener", address)
		}
	}
	for _, address := range []string{"127.0.0.1:8090", "[::1]:8090", "127.0.0.1:0"} {
		if err := validateMCPListen(address); err != nil {
			t.Fatal(err)
		}
	}
	for _, secret := range []string{"", "short", strings.Repeat("x", 32) + "\n"} {
		if _, err := mcpHTTPToken("", func(string) string { return secret }); err == nil {
			t.Fatal("invalid HTTP secret")
		}
	}
	path := filepath.Join(t.TempDir(), "http-token")
	token := strings.Repeat("y", 40)
	_ = os.WriteFile(path, []byte(token+"\n"), 0600)
	if value, err := mcpHTTPToken(path, func(string) string { return "ignored" }); err != nil || value != token {
		t.Fatal("token file", err)
	}
	if _, err := newMCPBridge(nil, 0, mcpOptions{Roots: []string{filepath.Join(t.TempDir(), "missing")}}); err == nil {
		t.Fatal("invalid upload root accepted")
	}
	if message := mcpAPIError(fmt.Errorf("proxy /private/path password=secret")).Error(); strings.Contains(message, "private") || strings.Contains(message, "secret") {
		t.Fatal("transport diagnostics leaked")
	}
}

type mcpNoticeWriter chan string

func (w mcpNoticeWriter) Write(data []byte) (int, error) { w <- string(data); return len(data), nil }

func TestMCPHTTPApplicationLifecycle(t *testing.T) {
	h := testCLI(t, func(w http.ResponseWriter, r *http.Request) { writeJSON(w, []endpoint{{1, "Native listener", true}}) })
	token := strings.Repeat("l", 40)
	h.env["CLAMAV_MCP_HTTP_TOKEN"] = token
	ready := make(mcpNoticeWriter, 1)
	a := New(strings.NewReader(""), io.Discard, ready, "2.0.0")
	a.Getenv = func(key string) string { return h.env[key] }
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	finished := make(chan int, 1)
	go func() {
		finished <- a.Run(ctx, []string{"--config", h.config, "--server", h.server.URL, "--ca-file", h.ca, "mcp", "serve", "--transport=http", "--listen=127.0.0.1:0"})
	}()
	var address string
	select {
	case notice := <-ready:
		prefix := "clamctl: MCP Streamable HTTP listening on "
		if !strings.HasPrefix(notice, prefix) {
			t.Fatal("unexpected startup message", notice)
		}
		address = strings.Fields(strings.TrimPrefix(notice, prefix))[0]
	case <-time.After(5 * time.Second):
		t.Fatal("listener did not start")
	}
	client := mcp.NewClient(&mcp.Implementation{Name: "lifecycle", Version: "1"}, nil)
	session, err := client.Connect(ctx, &mcp.StreamableClientTransport{Endpoint: "http://" + address + "/mcp", HTTPClient: &http.Client{Transport: mcpAuthTransport{http.DefaultTransport, token}, Timeout: time.Second}, DisableStandaloneSSE: true}, nil)
	if err != nil {
		t.Fatal(err)
	}
	mcpCall(t, session, "clamav_list_endpoints", map[string]any{}, false)
	if err := session.Close(); err != nil {
		t.Fatal(err)
	}
	cancel()
	select {
	case code := <-finished:
		if code != 0 {
			t.Fatal("shutdown failed", code)
		}
	case <-time.After(6 * time.Second):
		t.Fatal("listener did not stop")
	}
}
