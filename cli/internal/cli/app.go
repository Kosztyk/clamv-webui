package cli

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"time"
)

type App struct {
	In        io.Reader
	Out, Err  io.Writer
	Version   string
	Getenv    func(string) string
	Transport http.RoundTripper
}

func New(in io.Reader, out, stderr io.Writer, version string) *App {
	return &App{In: in, Out: out, Err: stderr, Version: version, Getenv: os.Getenv}
}

type failure struct {
	code    int
	message string
}

func (f *failure) Error() string { return f.message }
func usage(message string) error { return &failure{2, message} }

type globals struct {
	JSON                   bool
	Server, Config, CAFile string
	Endpoint               int64
	RequestTimeout         time.Duration
}

func parseGlobals(args []string) (globals, []string, error) {
	options := globals{RequestTimeout: 2 * time.Minute}
	var remaining []string
	for i := 0; i < len(args); i++ {
		arg := args[i]
		if arg == "--" {
			remaining = append(remaining, args[i:]...)
			break
		}
		key, value, assigned := strings.Cut(arg, "=")
		if key == "--json" {
			options.JSON = true
			if assigned {
				parsed, err := strconv.ParseBool(value)
				if err != nil {
					return options, nil, usage("invalid --json value")
				}
				options.JSON = parsed
			}
			continue
		}
		if key != "--server" && key != "--config" && key != "--ca-file" && key != "--endpoint" && key != "--request-timeout" {
			remaining = append(remaining, arg)
			if !assigned && commandValueFlag(key) && i+1 < len(args) {
				i++
				remaining = append(remaining, args[i])
			}
			continue
		}
		if !assigned {
			i++
			if i >= len(args) {
				return options, nil, usage("missing global option value")
			}
			value = args[i]
		}
		switch key {
		case "--server":
			options.Server = value
		case "--config":
			options.Config = value
		case "--ca-file":
			options.CAFile = value
		case "--endpoint":
			parsed, err := strconv.ParseInt(value, 10, 64)
			if err != nil || parsed < 1 {
				return options, nil, usage("--endpoint must be a positive ID")
			}
			options.Endpoint = parsed
		case "--request-timeout":
			parsed, err := time.ParseDuration(value)
			if err != nil || parsed <= 0 {
				return options, nil, usage("--request-timeout must be a positive duration")
			}
			options.RequestTimeout = parsed
		}
	}
	return options, remaining, nil
}

// The standard flag package stops at the first operand. Keep flags usable after filenames.
func parseFlags(args []string, fs *flag.FlagSet) error {
	var options, operands []string
	for i := 0; i < len(args); i++ {
		arg := args[i]
		if arg == "--" {
			operands = append(operands, args[i+1:]...)
			break
		}
		if !strings.HasPrefix(arg, "-") || arg == "-" {
			operands = append(operands, arg)
			continue
		}
		name, _, assigned := strings.Cut(strings.TrimLeft(arg, "-"), "=")
		option := fs.Lookup(name)
		if option == nil {
			return usage("unknown option; see clamctl --help")
		}
		options = append(options, arg)
		boolean, ok := option.Value.(interface{ IsBoolFlag() bool })
		if !assigned && !(ok && boolean.IsBoolFlag()) {
			i++
			if i >= len(args) {
				return usage("missing command option value")
			}
			options = append(options, args[i])
		}
	}
	fs.SetOutput(io.Discard)
	if fs.Parse(append(append(options, "--"), operands...)) != nil {
		return usage("invalid command options; see clamctl --help")
	}
	return nil
}

func helpRequested(args []string) bool {
	for i := 0; i < len(args); i++ {
		arg := args[i]
		if arg == "--" {
			break
		}
		if arg == "--help" || arg == "-h" {
			return true
		}
		key, _, assigned := strings.Cut(arg, "=")
		if !assigned && commandValueFlag(key) {
			i++
		}
	}
	return false
}

func commandValueFlag(value string) bool {
	return oneOf(value, "--username", "--password", "--token", "--status", "--verdict", "--limit", "--page", "--interval", "--timeout", "--transport", "--upload-root", "--listen", "--http-token-file", "--allowed-origin", "--max-upload-bytes")
}

func (a *App) Run(ctx context.Context, args []string) int {
	options, words, err := parseGlobals(args)
	code := 0
	if err == nil {
		code, err = a.run(ctx, options, words)
	}
	if err == nil {
		return code
	}
	code = 1
	var problem *failure
	if errors.As(err, &problem) {
		code = problem.code
	}
	if errors.Is(err, context.DeadlineExceeded) {
		code = 4
	}
	if errors.Is(err, context.Canceled) {
		code = 130
	}
	if options.JSON {
		_ = json.NewEncoder(a.Err).Encode(map[string]any{"error": safeCell(err.Error()), "exitCode": code})
	} else {
		fmt.Fprintln(a.Err, "clamctl:", safeCell(err.Error()))
	}
	return code
}

func (a *App) run(ctx context.Context, options globals, words []string) (int, error) {
	if len(words) == 0 || words[0] == "help" || helpRequested(words) {
		_, err := fmt.Fprint(a.Out, helpText)
		return 0, err
	}
	if words[0] == "version" || words[0] == "--version" {
		value := map[string]string{"version": a.Version, "os": runtime.GOOS, "arch": runtime.GOARCH}
		return 0, a.output(options, value, []string{"VERSION", "OS", "ARCH"}, [][]string{{a.Version, runtime.GOOS, runtime.GOARCH}})
	}
	if options.Config == "" {
		options.Config = a.Getenv("CLAMAV_CONFIG")
	}
	if options.Config == "" {
		parent, err := os.UserConfigDir()
		if err != nil {
			return 2, usage("cannot locate user configuration directory; use --config")
		}
		options.Config = filepath.Join(parent, "clamctl", "config.json")
	}
	saved, err := loadConfig(options.Config)
	if err != nil {
		return 2, err
	}
	if words[0] == "config" {
		return a.configure(options, words[1:], saved)
	}
	if words[0] == "logout" {
		if len(words) != 1 {
			return 2, usage("usage: clamctl logout")
		}
		saved.Auth = credential{}
		if err := saveConfig(options.Config, saved); err != nil {
			return 1, err
		}
		return 0, a.output(options, map[string]bool{"loggedOut": true}, []string{"AUTHENTICATION"}, [][]string{{"Stored credentials removed"}})
	}
	server := options.Server
	if server == "" {
		server = a.Getenv("CLAMAV_SERVER")
	}
	if server == "" {
		server = saved.Server
	}
	if server == "" {
		return 2, usage("configure a server with clamctl config set-server https://host")
	}
	server, err = serverURL(server)
	if err != nil {
		return 2, err
	}
	caFile := options.CAFile
	if caFile == "" {
		caFile = a.Getenv("CLAMAV_CA_FILE")
	}
	if caFile == "" {
		caFile = saved.CAFile
	}
	if words[0] == "login" {
		return a.login(ctx, options, words[1:], saved, server, caFile)
	}
	auth, err := resolveAuth(saved, a.Getenv, server)
	if err != nil {
		return 2, err
	}
	client, err := newClient(server, auth, caFile, options.RequestTimeout, a.Transport)
	if err != nil {
		return 2, err
	}
	defer client.close()
	switch words[0] {
	case "mcp":
		return a.serveMCP(ctx, options, words[1:], client)
	case "status":
		if len(words) != 1 {
			return 2, usage("usage: clamctl status [--endpoint ID]")
		}
		return a.status(ctx, options, client)
	case "endpoints":
		if len(words) != 2 || words[1] != "list" {
			return 2, usage("usage: clamctl endpoints list")
		}
		var values []endpoint
		if err := client.request(ctx, "GET", "/endpoints", nil, nil, "", &values, false); err != nil {
			return 1, err
		}
		rows := [][]string{}
		for _, value := range values {
			rows = append(rows, []string{fmt.Sprint(value.ID), value.Name, strconv.FormatBool(value.Enabled)})
		}
		return 0, a.output(options, values, []string{"ID", "NAME", "ENABLED"}, rows)
	case "scan":
		return a.scan(ctx, options, words[1:], client)
	case "jobs":
		return a.jobs(ctx, options, words[1:], client)
	case "audit":
		return a.audit(ctx, options, words[1:], client)
	default:
		return 2, usage("unknown command; see clamctl --help")
	}
}

func (a *App) configure(options globals, args []string, saved config) (int, error) {
	if len(args) == 2 && args[0] == "set-server" {
		server, err := serverURL(args[1])
		if err != nil {
			return 2, err
		}
		if saved.Server != server {
			saved.Auth = credential{}
		}
		saved.Server = server
		if options.CAFile != "" {
			saved.CAFile, err = filepath.Abs(options.CAFile)
			if err != nil {
				return 2, usage("invalid CA file path")
			}
		}
		if err := saveConfig(options.Config, saved); err != nil {
			return 1, err
		}
		return 0, a.output(options, map[string]string{"server": server}, []string{"SERVER"}, [][]string{{server}})
	}
	if len(args) == 1 && args[0] == "show" {
		value := map[string]any{"server": saved.Server, "caFile": saved.CAFile, "authentication": saved.Auth.mode(), "username": saved.Auth.Username, "credentialStored": saved.Auth.mode() != "none"}
		return 0, a.output(options, value, []string{"SETTING", "VALUE"}, [][]string{{"Server", saved.Server}, {"CA file", saved.CAFile}, {"Authentication", saved.Auth.mode()}, {"Username", saved.Auth.Username}})
	}
	return 2, usage("usage: clamctl config set-server URL | config show")
}

func (a *App) login(ctx context.Context, options globals, args []string, saved config, server, caFile string) (int, error) {
	fs := flag.NewFlagSet("login", flag.ContinueOnError)
	username := fs.String("username", "", "Basic username")
	password := fs.String("password", "", "Basic password")
	token := fs.String("token", "", "API token")
	passwordStdin := fs.Bool("password-stdin", false, "Read password from standard input")
	tokenStdin := fs.Bool("token-stdin", false, "Read token from standard input")
	if err := parseFlags(args, fs); err != nil {
		return 2, err
	}
	if fs.NArg() != 0 || (*passwordStdin && *tokenStdin) || (*passwordStdin && *password != "") || (*tokenStdin && *token != "") {
		return 2, usage("choose one credential input method")
	}
	supplied := map[string]bool{}
	fs.Visit(func(option *flag.Flag) { supplied[option.Name] = true })
	if *passwordStdin || *tokenStdin {
		bytes, err := readCredential(ctx, a.In)
		if err != nil {
			return 2, err
		}
		value := strings.TrimSuffix(strings.TrimSuffix(string(bytes), "\n"), "\r")
		if *passwordStdin {
			*password = value
		} else {
			*token = strings.TrimSpace(value)
		}
	}
	var auth credential
	if (supplied["token"] || *tokenStdin) && *token == "" {
		return 2, usage("API token is empty")
	}
	if *token != "" {
		auth = credential{Token: *token}
	}
	if supplied["username"] || supplied["password"] || *passwordStdin {
		if auth.mode() != "none" || *tokenStdin {
			return 2, usage("choose token or Basic authentication")
		}
		if *username == "" {
			*username = a.Getenv("CLAMAV_USERNAME")
		}
		if *password == "" && !supplied["password"] && !*passwordStdin {
			*password = a.Getenv("CLAMAV_PASSWORD")
		}
		if *username == "" || *password == "" {
			return 2, usage("Basic login needs --username and --password-stdin, --password or CLAMAV_PASSWORD")
		}
		auth = credential{Username: *username, Password: *password}
	}
	if caFile != "" {
		var err error
		caFile, err = filepath.Abs(caFile)
		if err != nil {
			return 2, usage("invalid CA file path")
		}
	}
	if auth.mode() == "none" {
		var err error
		auth, err = resolveAuth(saved, a.Getenv, server)
		if err != nil {
			return 2, err
		}
	}
	client, err := newClient(server, auth, caFile, options.RequestTimeout, a.Transport)
	if err != nil {
		return 2, err
	}
	defer client.close()
	var identity struct {
		Authenticated bool   `json:"authenticated"`
		Principal     string `json:"principal"`
	}
	if err := client.request(ctx, "GET", "/auth", nil, nil, "", &identity, false); err != nil {
		return 1, err
	}
	if !identity.Authenticated {
		return 1, fmt.Errorf("server did not confirm authentication")
	}
	saved.Server = server
	saved.CAFile = caFile
	saved.Auth = auth
	if err := saveConfig(options.Config, saved); err != nil {
		return 1, err
	}
	return 0, a.output(options, map[string]any{"authenticated": true, "principal": identity.Principal, "authentication": auth.mode()},
		[]string{"PRINCIPAL", "AUTHENTICATION"}, [][]string{{identity.Principal, auth.mode()}})
}

func readCredential(ctx context.Context, input io.Reader) ([]byte, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	type result struct {
		bytes []byte
		err   error
	}
	finished := make(chan result, 1)
	go func() { bytes, err := io.ReadAll(io.LimitReader(input, 65537)); finished <- result{bytes, err} }()
	select {
	case <-ctx.Done():
		if closer, ok := input.(io.Closer); ok {
			_ = closer.Close()
		}
		return nil, ctx.Err()
	case value := <-finished:
		if value.err != nil || len(value.bytes) > 65536 {
			return nil, usage("cannot read credential from standard input")
		}
		return value.bytes, nil
	}
}

func endpointQuery(options globals) url.Values {
	query := url.Values{}
	if options.Endpoint > 0 {
		query.Set("endpointId", fmt.Sprint(options.Endpoint))
	}
	return query
}

func (a *App) status(ctx context.Context, options globals, client *apiClient) (int, error) {
	var report struct {
		Health  health  `json:"health"`
		Metrics metrics `json:"metrics"`
	}
	query := endpointQuery(options)
	if err := client.request(ctx, "GET", "/health/clamd", query, nil, "", &report.Health, true); err != nil {
		return 1, err
	}
	if err := client.request(ctx, "GET", "/metrics/clamd", query, nil, "", &report.Metrics, false); err != nil {
		return 1, err
	}
	h, m := report.Health, report.Metrics
	rows := [][]string{{"Endpoint", h.Endpoint}, {"Connectivity", h.Connectivity}, {"ClamD version", h.Version},
		{"Signature database", h.DatabaseVersion}, {"Database freshness", h.Freshness}, {"Pools", pointer(m.Pools)},
		{"Threads live", count(m.Threads, "live")}, {"Threads idle", count(m.Threads, "idle")}, {"Threads busy", count(m.Threads, "busy")},
		{"Threads max", count(m.Threads, "max")}, {"Queue items", count(m.Queue, "items")},
		{"Pool memory used", m.Memory["pools_used"]}, {"Pool memory total", m.Memory["pools_total"]}}
	code := 0
	if h.Connectivity != "UP" || h.Freshness == "STALE" || !m.OK {
		code = 1
	}
	return code, a.output(options, report, []string{"METRIC", "VALUE"}, rows)
}

var jobIDPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

func validJobID(value string) error {
	if !jobIDPattern.MatchString(value) {
		return usage("invalid job ID")
	}
	return nil
}

type waitOptions struct {
	Wait              bool
	Interval, Timeout time.Duration
}

func scanFlags(fs *flag.FlagSet) *waitOptions {
	options := &waitOptions{}
	fs.BoolVar(&options.Wait, "wait", false, "Wait for the final verdict")
	fs.DurationVar(&options.Interval, "interval", 2*time.Second, "Job polling interval")
	fs.DurationVar(&options.Timeout, "timeout", 30*time.Minute, "Overall wait timeout")
	return options
}

func (a *App) scan(ctx context.Context, options globals, args []string, client *apiClient) (int, error) {
	if len(args) == 0 {
		return 2, usage("usage: clamctl scan upload FILE... | scan path SERVER_PATH")
	}
	fs := flag.NewFlagSet("scan", flag.ContinueOnError)
	wait := scanFlags(fs)
	if err := parseFlags(args[1:], fs); err != nil {
		return 2, err
	}
	if fs.NArg() == 0 || wait.Interval <= 0 || wait.Timeout <= 0 {
		return 2, usage("scan requires a path and positive polling interval/timeout")
	}
	var submission submitted
	switch args[0] {
	case "upload":
		var err error
		submission, err = client.upload(ctx, fs.Args(), options.Endpoint)
		if err != nil {
			return 1, err
		}
	case "path":
		if fs.NArg() != 1 {
			return 2, usage("scan path accepts one server-side path")
		}
		body := map[string]any{"path": fs.Arg(0)}
		if options.Endpoint > 0 {
			body["endpointId"] = options.Endpoint
		}
		encoded, _ := json.Marshal(body)
		var result struct {
			JobID string `json:"jobId"`
		}
		if err := client.request(ctx, "POST", "/scans/paths", nil, bytes.NewReader(encoded), "application/json", &result, false); err != nil {
			return 1, err
		}
		submission = submitted{Created: 1, JobIDs: []string{result.JobID}}
	default:
		return 2, usage("usage: clamctl scan upload FILE... | scan path SERVER_PATH")
	}
	for _, id := range submission.JobIDs {
		if err := validJobID(id); err != nil {
			return 1, fmt.Errorf("server returned an invalid job ID")
		}
	}
	if !wait.Wait {
		rows := [][]string{}
		for _, id := range submission.JobIDs {
			rows = append(rows, []string{id, "QUEUED"})
		}
		return 0, a.output(options, submission, []string{"JOB", "STATUS"}, rows)
	}
	waitCtx, cancel := context.WithTimeout(ctx, wait.Timeout)
	defer cancel()
	results := []job{}
	code := 0
	for _, id := range submission.JobIDs {
		value, err := a.waitJob(waitCtx, options, client, id, wait.Interval, false)
		if err != nil {
			return 1, fmt.Errorf("job %s: %w", id, err)
		}
		results = append(results, value)
		if result := jobExit(value); result == 1 || (result == 3 && code == 0) {
			code = result
		}
	}
	if len(results) == 1 {
		return code, a.showJob(options, results[0])
	}
	return code, a.output(options, map[string]any{"jobs": results}, jobHeaders, jobRows(results))
}

func (a *App) jobs(ctx context.Context, options globals, args []string, client *apiClient) (int, error) {
	if len(args) == 0 {
		return 2, usage("usage: clamctl jobs list | jobs get ID | jobs watch ID")
	}
	switch args[0] {
	case "list":
		fs := flag.NewFlagSet("jobs list", flag.ContinueOnError)
		limit := fs.Int("limit", 25, "Jobs per page (1-100)")
		number := fs.Int("page", 0, "Zero-based page")
		status := fs.String("status", "", "QUEUED, RUNNING or FINISHED")
		verdict := fs.String("verdict", "", "OK, VIRUS_FOUND or ERROR")
		if err := parseFlags(args[1:], fs); err != nil {
			return 2, err
		}
		if fs.NArg() != 0 || *limit < 1 || *limit > 100 || *number < 0 {
			return 2, usage("invalid job pagination")
		}
		*status = strings.ToUpper(*status)
		*verdict = strings.ToUpper(*verdict)
		if !oneOf(*status, "", "QUEUED", "RUNNING", "FINISHED") || !oneOf(*verdict, "", "OK", "VIRUS_FOUND", "ERROR") {
			return 2, usage("invalid job status or verdict filter")
		}
		query := endpointQuery(options)
		query.Set("page", fmt.Sprint(*number))
		query.Set("size", fmt.Sprint(*limit))
		if *status != "" {
			query.Set("status", *status)
		}
		if *verdict != "" {
			query.Set("verdict", *verdict)
		}
		var result page[job]
		if err := client.request(ctx, "GET", "/jobs", query, nil, "", &result, false); err != nil {
			return 1, err
		}
		return 0, a.output(options, result, jobHeaders, jobRows(result.Items))
	case "get", "watch":
		fs := flag.NewFlagSet("jobs", flag.ContinueOnError)
		wait := scanFlags(fs)
		if err := parseFlags(args[1:], fs); err != nil {
			return 2, err
		}
		if fs.NArg() != 1 {
			return 2, usage("specify exactly one job ID")
		}
		id := fs.Arg(0)
		if err := validJobID(id); err != nil {
			return 2, err
		}
		if args[0] == "get" {
			value, err := getJob(ctx, client, id)
			if err != nil {
				return 1, err
			}
			return jobExit(value), a.showJob(options, value)
		}
		if wait.Interval <= 0 || wait.Timeout <= 0 {
			return 2, usage("polling interval and timeout must be positive")
		}
		waitCtx, cancel := context.WithTimeout(ctx, wait.Timeout)
		defer cancel()
		value, err := a.waitJob(waitCtx, options, client, id, wait.Interval, true)
		if err != nil {
			return 1, err
		}
		if options.JSON {
			return jobExit(value), json.NewEncoder(a.Out).Encode(map[string]any{"event": "result", "job": value})
		}
		return jobExit(value), a.showJob(options, value)
	default:
		return 2, usage("usage: clamctl jobs list | jobs get ID | jobs watch ID")
	}
}

func getJob(ctx context.Context, client *apiClient, id string) (job, error) {
	var value job
	err := client.request(ctx, "GET", "/jobs/"+id, nil, nil, "", &value, false)
	if err == nil && value.ID != id {
		return value, fmt.Errorf("server returned a different job ID")
	}
	if value.Signatures == nil {
		value.Signatures = []string{}
	}
	if value.DurationMillis == nil && value.StartedAt != nil && value.FinishedAt != nil && !value.FinishedAt.Before(*value.StartedAt) {
		duration := value.FinishedAt.Sub(*value.StartedAt).Milliseconds()
		value.DurationMillis = &duration
	}
	return value, err
}

func (a *App) waitJob(ctx context.Context, options globals, client *apiClient, id string, interval time.Duration, stream bool) (job, error) {
	last := ""
	for {
		var value progress
		if err := client.request(ctx, "GET", "/jobs/"+id+"/progress", nil, nil, "", &value, false); err != nil {
			return job{}, err
		}
		if value.JobID != id || !oneOf(value.Status, "QUEUED", "RUNNING", "FINISHED") {
			return job{}, fmt.Errorf("server returned invalid job progress")
		}
		key := value.Status + "/" + value.Verdict
		if key != last {
			if stream && options.JSON {
				if err := json.NewEncoder(a.Out).Encode(map[string]any{"event": "progress", "progress": value}); err != nil {
					return job{}, err
				}
			} else if !options.JSON {
				writer := a.Err
				if stream {
					writer = a.Out
				}
				fmt.Fprintf(writer, "%s  %s  %s\n", id, safeCell(value.Status), safeCell(value.Verdict))
			}
			last = key
		}
		if value.Completed || value.Status == "FINISHED" {
			return getJob(ctx, client, id)
		}
		timer := time.NewTimer(interval)
		select {
		case <-ctx.Done():
			timer.Stop()
			return job{}, ctx.Err()
		case <-timer.C:
		}
	}
}

func (a *App) audit(ctx context.Context, options globals, args []string, client *apiClient) (int, error) {
	if len(args) == 0 || args[0] != "list" {
		return 2, usage("usage: clamctl audit list [--limit 20]")
	}
	fs := flag.NewFlagSet("audit list", flag.ContinueOnError)
	limit := fs.Int("limit", 20, "Events per page (1-100)")
	number := fs.Int("page", 0, "Zero-based page")
	if err := parseFlags(args[1:], fs); err != nil {
		return 2, err
	}
	if fs.NArg() != 0 || *limit < 1 || *limit > 100 || *number < 0 {
		return 2, usage("invalid audit pagination")
	}
	query := url.Values{"size": {fmt.Sprint(*limit)}, "page": {fmt.Sprint(*number)}}
	var result page[audit]
	if err := client.request(ctx, "GET", "/audit", query, nil, "", &result, false); err != nil {
		return 1, err
	}
	rows := [][]string{}
	for _, event := range result.Items {
		rows = append(rows, []string{fmt.Sprint(event.ID), event.At.Format(time.RFC3339), event.Actor, event.Action, event.Outcome})
	}
	return 0, a.output(options, result, []string{"ID", "AT", "ACTOR", "ACTION", "OUTCOME"}, rows)
}

func oneOf(value string, allowed ...string) bool {
	for _, candidate := range allowed {
		if value == candidate {
			return true
		}
	}
	return false
}

const helpText = `clamctl - ClamAV Web UI CLI

Configuration and authentication:
  clamctl config set-server https://clamav.example.com
  clamctl config show
  clamctl login --username admin --password-stdin
  clamctl login --token-stdin
  clamctl logout

Health and scanning:
  clamctl status [--endpoint ID]
  clamctl endpoints list
  clamctl scan upload FILE... [--wait] [--json]
  clamctl scan path SERVER_PATH [--wait] [--endpoint ID]
  clamctl jobs list [--status RUNNING] [--verdict VIRUS_FOUND] [--limit 25] [--page 0]
  clamctl jobs get JOB_ID
  clamctl jobs watch JOB_ID [--interval 2s] [--timeout 30m] [--json]
  clamctl audit list [--limit 20] [--page 0]
  clamctl mcp serve [--upload-root DIRECTORY] [--transport stdio|http]
  clamctl version

Global options may appear before or after command operands:
  --server URL, --config FILE, --ca-file PEM, --endpoint ID, --json
  --request-timeout 2m
Wait/watch options: --interval 2s --timeout 30m
MCP: --upload-root DIRECTORY (repeatable; local uploads denied without a root),
  --transport stdio (default) or http, --listen 127.0.0.1:8090,
  --http-token-file FILE (or CLAMAV_MCP_HTTP_TOKEN), --allowed-origin HTTPS_ORIGIN,
  --max-upload-bytes 104857600, --interval 2s, --timeout 30m.
Environment: CLAMAV_SERVER, CLAMAV_API_TOKEN (or CLAMAV_API_KEY),
  CLAMAV_USERNAME, CLAMAV_PASSWORD, CLAMAV_CA_FILE, CLAMAV_CONFIG.
Login also accepts --token TOKEN or --password PASSWORD.
Authentication uses HTTPS with certificate verification. Saved credentials are
stored in the user configuration file; config show never prints secrets.
JSON: one document per command; jobs watch emits newline-delimited events.
Exit codes: 0 success/accepted, 1 API or scan error/unhealthy daemon,
  2 usage/configuration, 3 malware found, 4 timeout, 130 interrupted.
`
