//go:build linux || darwin

package cli

import (
	"io"
	"net/http"
	"os"
	"path/filepath"
	"syscall"
	"testing"
	"time"
)

func TestMCPFIFOIsRejectedWithoutBlocking(t *testing.T) {
	b, root := testMCPBridge(t, func(http.ResponseWriter, *http.Request) { t.Error("unexpected API request") })
	path := filepath.Join(root, "pipe")
	if err := syscall.Mkfifo(path, 0600); err != nil {
		t.Fatal(err)
	}
	finished := make(chan error, 1)
	go func() {
		file, err := b.openUpload(path)
		if file != nil {
			file.Close()
		}
		finished <- err
	}()
	select {
	case err := <-finished:
		if err == nil {
			t.Fatal("FIFO accepted")
		}
	case <-time.After(time.Second):
		t.Fatal("opening a FIFO blocked the MCP request")
	}
}

func TestMCPUploadRootHandleSurvivesDirectoryReplacement(t *testing.T) {
	b, root := testMCPBridge(t, func(http.ResponseWriter, *http.Request) { t.Error("unexpected API request") })
	name := "sample"
	if err := os.WriteFile(filepath.Join(root, name), []byte("authorized"), 0600); err != nil {
		t.Fatal(err)
	}
	outside := t.TempDir()
	if err := os.WriteFile(filepath.Join(outside, name), []byte("outside"), 0600); err != nil {
		t.Fatal(err)
	}
	moved := root + "-original"
	if err := os.Rename(root, moved); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Rename(moved, root) })
	if err := os.Symlink(outside, root); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Remove(root) })
	file, err := b.openUpload(filepath.Join(root, name))
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	data, err := io.ReadAll(file)
	if err != nil || string(data) != "authorized" {
		t.Fatal("replaced root escaped the authorized handle", err)
	}
}
