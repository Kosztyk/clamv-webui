package cli

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"
	"unicode"
)

const maxResponse = 8 * 1024 * 1024

type apiClient struct {
	base string
	auth credential
	http *http.Client
}

func newClient(base string, auth credential, caFile string, timeout time.Duration, transport http.RoundTripper) (*apiClient, error) {
	if auth.mode() == "none" {
		return nil, usage("authentication is required")
	}
	if strings.Contains(auth.Username, ":") || strings.ContainsAny(auth.Token, "\r\n") {
		return nil, usage("invalid authentication value")
	}
	if transport == nil {
		tr := http.DefaultTransport.(*http.Transport).Clone()
		tr.TLSClientConfig = &tls.Config{MinVersion: tls.VersionTLS12}
		if caFile != "" {
			pem, err := os.ReadFile(caFile)
			if err != nil || len(pem) > 2*1024*1024 {
				return nil, usage("cannot read CA certificate file")
			}
			roots, err := x509.SystemCertPool()
			if err != nil {
				roots = x509.NewCertPool()
			}
			if !roots.AppendCertsFromPEM(pem) {
				return nil, usage("CA file contains no usable PEM certificates")
			}
			tr.TLSClientConfig.RootCAs = roots
		}
		transport = tr
	}
	jar, _ := cookiejar.New(nil)
	return &apiClient{base: base, auth: auth, http: &http.Client{
		Transport: transport, Timeout: timeout, Jar: jar,
		// No redirect may forward authentication or replay a scan submission.
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}}, nil
}

func (c *apiClient) close() { c.http.CloseIdleConnections() }

func (c *apiClient) request(ctx context.Context, method, path string, query url.Values, body io.Reader, contentType string, out any, acceptUnavailable bool) error {
	var csrf struct {
		Token  string `json:"token"`
		Header string `json:"headerName"`
	}
	if method != http.MethodGet && c.auth.mode() == "basic" {
		if err := c.request(ctx, http.MethodGet, "/csrf", nil, nil, "", &csrf, false); err != nil {
			return err
		}
		if (csrf.Header != "X-CSRF-TOKEN" && csrf.Header != "X-XSRF-TOKEN") || csrf.Token == "" || len(csrf.Token) > 8192 || strings.ContainsAny(csrf.Token, "\r\n") {
			return fmt.Errorf("server returned an invalid CSRF response")
		}
	}
	u, _ := url.Parse(c.base + "/api/v1" + path)
	u.RawQuery = query.Encode()
	req, err := http.NewRequestWithContext(ctx, method, u.String(), body)
	if err != nil {
		return fmt.Errorf("cannot construct API request")
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", "clamctl/2.0.0")
	if c.auth.mode() == "token" {
		req.Header.Set("Authorization", "Bearer "+c.auth.Token)
	} else {
		req.SetBasicAuth(c.auth.Username, c.auth.Password)
	}
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	if csrf.Token != "" {
		req.Header.Set(csrf.Header, csrf.Token)
	}
	response, err := c.http.Do(req)
	if err != nil {
		return fmt.Errorf("API request failed: %w", err)
	}
	defer response.Body.Close()
	data, err := io.ReadAll(io.LimitReader(response.Body, maxResponse+1))
	if err != nil {
		return fmt.Errorf("cannot read API response: %w", err)
	}
	if len(data) > maxResponse {
		return fmt.Errorf("API response exceeds the size limit")
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		if !(acceptUnavailable && response.StatusCode == 503) {
			var problem struct {
				Error string `json:"error"`
			}
			_ = json.Unmarshal(data, &problem)
			if !oneOf(problem.Error, "unauthorized", "invalid_token", "tls_required", "forbidden", "invalid_request", "not_found", "request_failed", "internal_error", "upload_too_large", "rate_limit_exceeded", "rate_limited") {
				problem.Error = http.StatusText(response.StatusCode)
			}
			return fmt.Errorf("API returned HTTP %d (%s)", response.StatusCode, problem.Error)
		}
	}
	if out != nil && json.Unmarshal(data, out) != nil {
		return fmt.Errorf("server returned invalid JSON; check the server URL and API version")
	}
	return nil
}

func (c *apiClient) upload(ctx context.Context, paths []string, endpoint int64) (submitted, error) {
	var result submitted
	var files []*os.File
	defer func() {
		for _, file := range files {
			file.Close()
		}
	}()
	for _, path := range paths {
		file, err := os.Open(path)
		if err != nil {
			return result, usage("cannot open upload file")
		}
		files = append(files, file)
		info, err := file.Stat()
		if err != nil || !info.Mode().IsRegular() || info.Size() == 0 {
			return result, usage("uploads must be nonempty regular files")
		}
	}
	return c.uploadOpened(ctx, files, endpoint, 0)
}

// Callers own the handles. MCP supplies handles opened beneath os.Root so that
// symlink races cannot change an authorized path into an arbitrary local upload.
func (c *apiClient) uploadOpened(ctx context.Context, files []*os.File, endpoint, maxBytes int64) (submitted, error) {
	var result submitted
	reader, writer := io.Pipe()
	multipartWriter := multipart.NewWriter(writer)
	contentType := multipartWriter.FormDataContentType()
	finished := make(chan error, 1)
	go func() {
		var err error
		if endpoint > 0 {
			err = multipartWriter.WriteField("endpointId", fmt.Sprint(endpoint))
		}
		for _, file := range files {
			if err != nil {
				break
			}
			name := strings.Map(func(r rune) rune {
				if unicode.IsControl(r) {
					return '_'
				}
				return r
			}, filepath.Base(file.Name()))
			var part io.Writer
			part, err = multipartWriter.CreateFormFile("files", name)
			if err == nil {
				var source io.Reader = file
				if maxBytes > 0 {
					source = io.LimitReader(file, maxBytes+1)
				}
				var copied int64
				copied, err = io.Copy(part, source)
				if err == nil && maxBytes > 0 && copied > maxBytes {
					err = fmt.Errorf("upload exceeds the MCP size limit")
				}
			}
		}
		if err == nil {
			err = multipartWriter.Close()
		}
		writer.CloseWithError(err)
		finished <- err
	}()
	err := c.request(ctx, http.MethodPost, "/scans/uploads", nil, reader, contentType, &result, false)
	reader.CloseWithError(errors.New("upload request finished"))
	writeErr := <-finished
	if err != nil {
		return result, err
	}
	if writeErr != nil {
		return result, fmt.Errorf("could not stream the complete upload")
	}
	if result.Created < 1 || len(result.JobIDs) != result.Created {
		return result, fmt.Errorf("server returned an invalid upload submission")
	}
	return result, nil
}
