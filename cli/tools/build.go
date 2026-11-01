//go:build ignore

// Run from cli/: go run ./tools/build.go
package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strings"
)

func main() {
	version := flag.String("version", "2.1.0", "CLI version")
	out := flag.String("out", "dist", "Output directory")
	targets := flag.String("targets", "linux/amd64,linux/arm64,darwin/amd64,darwin/arm64,windows/amd64,windows/arm64", "Comma-separated GOOS/GOARCH pairs")
	flag.Parse()
	if !regexp.MustCompile(`^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9_.-]+)?$`).MatchString(*version) {
		fatal("invalid version")
	}
	if _, err := os.Stat("go.mod"); err != nil {
		fatal("run this builder from the cli directory")
	}
	goCommand := filepath.Join(runtime.GOROOT(), "bin", "go")
	if runtime.GOOS == "windows" {
		goCommand += ".exe"
	}
	if err := os.MkdirAll(*out, 0755); err != nil {
		fatal(err.Error())
	}
	var checksums []string
	var manifest []map[string]string
	for _, target := range strings.Split(*targets, ",") {
		parts := strings.Split(target, "/")
		if len(parts) != 2 || !allowed(parts[0], "linux", "darwin", "windows") || !allowed(parts[1], "amd64", "arm64") {
			fatal("unsupported target")
		}
		name := "clamctl"
		if parts[0] == "windows" {
			name += ".exe"
		}
		relative := parts[0] + "_" + parts[1] + "/" + name
		output := filepath.Join(*out, filepath.FromSlash(relative))
		if err := os.MkdirAll(filepath.Dir(output), 0755); err != nil {
			fatal(err.Error())
		}
		command := exec.Command(goCommand, "build", "-trimpath", "-buildvcs=false", "-ldflags=-s -w -buildid= -X main.version="+*version, "-o", output, "./cmd/clamctl")
		command.Env = buildEnvironment(parts[0], parts[1])
		command.Stdout = os.Stdout
		command.Stderr = os.Stderr
		fmt.Println("Building", target)
		if err := command.Run(); err != nil {
			fatal("cross-compilation failed")
		}
		if err := os.Chmod(output, 0755); err != nil {
			fatal(err.Error())
		}
		bytes, err := os.ReadFile(output)
		if err != nil {
			fatal(err.Error())
		}
		digest := sha256.Sum256(bytes)
		hash := hex.EncodeToString(digest[:])
		checksums = append(checksums, hash+"  "+relative)
		manifest = append(manifest, map[string]string{"os": parts[0], "arch": parts[1], "path": relative, "sha256": hash, "version": *version, "goVersion": runtime.Version(), "cgoEnabled": "0"})
	}
	sort.Strings(checksums)
	if err := os.WriteFile(filepath.Join(*out, "SHA256SUMS"), []byte(strings.Join(checksums, "\n")+"\n"), 0644); err != nil {
		fatal(err.Error())
	}
	data, err := json.MarshalIndent(manifest, "", "  ")
	if err != nil {
		fatal(err.Error())
	}
	if err := os.WriteFile(filepath.Join(*out, "manifest.json"), append(data, '\n'), 0644); err != nil {
		fatal(err.Error())
	}
	fmt.Println("Built standalone executables and SHA256SUMS in", *out)
}

func buildEnvironment(goos, goarch string) []string {
	settings := map[string]string{"GOOS": goos, "GOARCH": goarch, "CGO_ENABLED": "0", "GOTOOLCHAIN": "local"}
	var env []string
	for _, value := range os.Environ() {
		name, _, _ := strings.Cut(value, "=")
		if _, replace := settings[name]; !replace {
			env = append(env, value)
		}
	}
	for key, value := range settings {
		env = append(env, key+"="+value)
	}
	return env
}
func allowed(value string, choices ...string) bool {
	for _, candidate := range choices {
		if value == candidate {
			return true
		}
	}
	return false
}
func fatal(message string) { fmt.Fprintln(os.Stderr, message); os.Exit(1) }
