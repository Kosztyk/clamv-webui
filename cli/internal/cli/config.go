package cli

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

type credential struct {
	Token    string `json:"token,omitempty"`
	Username string `json:"username,omitempty"`
	Password string `json:"password,omitempty"`
}

func (c credential) mode() string {
	if c.Token != "" {
		return "token"
	}
	if c.Username != "" && c.Password != "" {
		return "basic"
	}
	return "none"
}

type config struct {
	Version int        `json:"version"`
	Server  string     `json:"server"`
	CAFile  string     `json:"caFile,omitempty"`
	Auth    credential `json:"auth"`
}

func loadConfig(path string) (config, error) {
	result := config{Version: 1}
	file, err := os.Open(path)
	if errors.Is(err, os.ErrNotExist) {
		return result, nil
	}
	if err != nil {
		return result, usage("cannot open CLI configuration")
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil || !info.Mode().IsRegular() || info.Size() > 1024*1024 {
		return result, usage("invalid CLI configuration file")
	}
	bytes, err := io.ReadAll(io.LimitReader(file, 1024*1024+1))
	if err != nil || json.Unmarshal(bytes, &result) != nil {
		return result, usage("cannot parse CLI configuration")
	}
	if result.Version != 1 {
		return result, usage("unsupported CLI configuration version")
	}
	if runtime.GOOS != "windows" && result.Auth.mode() != "none" && info.Mode().Perm()&0077 != 0 {
		return result, usage("credential file permissions must be 0600; restrict its permissions before continuing")
	}
	return result, nil
}

func saveConfig(path string, value config) error {
	parent := filepath.Dir(path)
	if err := os.MkdirAll(parent, 0700); err != nil {
		return fmt.Errorf("cannot create CLI configuration directory")
	}
	file, err := os.CreateTemp(parent, ".clamctl-*")
	if err != nil {
		return fmt.Errorf("cannot create CLI configuration file")
	}
	defer os.Remove(file.Name())
	defer file.Close()
	if err = file.Chmod(0600); err != nil {
		return fmt.Errorf("cannot restrict configuration file permissions")
	}
	encoder := json.NewEncoder(file)
	encoder.SetIndent("", "  ")
	if err = encoder.Encode(value); err == nil {
		err = file.Sync()
	}
	if err == nil {
		err = file.Close()
	}
	if err == nil {
		err = os.Rename(file.Name(), path)
	}
	if err != nil {
		return fmt.Errorf("cannot save CLI configuration")
	}
	return nil
}

func serverURL(raw string) (string, error) {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || u.Scheme != "https" || u.Hostname() == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || u.Opaque != "" {
		return "", usage("server must be an HTTPS URL without credentials, query parameters or a fragment")
	}
	u.Path = strings.TrimSuffix(strings.TrimRight(u.Path, "/"), "/api/v1")
	u.RawPath = ""
	return strings.TrimRight(u.String(), "/"), nil
}

func resolveAuth(saved config, getenv func(string) string, server string) (credential, error) {
	for _, key := range []string{"CLAMAV_API_TOKEN", "CLAMAV_API_KEY"} {
		if value := strings.TrimSpace(getenv(key)); value != "" {
			return credential{Token: value}, nil
		}
	}
	username, password := getenv("CLAMAV_USERNAME"), getenv("CLAMAV_PASSWORD")
	if username != "" || password != "" {
		if username == "" || password == "" {
			return credential{}, usage("set both CLAMAV_USERNAME and CLAMAV_PASSWORD")
		}
		return credential{Username: username, Password: password}, nil
	}
	if saved.Server != "" && server != saved.Server && saved.Auth.mode() != "none" {
		return credential{}, usage("server override differs from the saved server; provide explicit credentials or configure and log in to the new server")
	}
	if saved.Auth.mode() == "none" {
		return credential{}, usage("log in or set CLAMAV_API_TOKEN / CLAMAV_API_KEY")
	}
	return saved.Auth, nil
}
