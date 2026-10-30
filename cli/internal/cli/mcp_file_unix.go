//go:build linux || darwin

package cli

import (
	"os"
	"syscall"
)

func openMCPFile(root *os.Root, name string) (*os.File, error) {
	// A FIFO replacing a regular file must not block before the fstat check.
	return root.OpenFile(name, os.O_RDONLY|syscall.O_NONBLOCK, 0)
}
