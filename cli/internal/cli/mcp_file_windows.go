package cli

import "os"

func openMCPFile(root *os.Root, name string) (*os.File, error) {
	return root.Open(name)
}
