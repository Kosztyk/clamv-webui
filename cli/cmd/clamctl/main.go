package main

import (
	"context"
	"os"
	"os/signal"
	"syscall"

	app "github.com/Kosztyk/clamv-webui/cli/internal/cli"
)

var version = "2.1.0"

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	code := app.New(os.Stdin, os.Stdout, os.Stderr, version).Run(ctx, os.Args[1:])
	stop()
	os.Exit(code)
}
