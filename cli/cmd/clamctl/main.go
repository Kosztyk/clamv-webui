package main

import (
	"context"
	"os"
	"os/signal"
	"syscall"

	app "github.com/Kosztyk/clamv-webui/cli/internal/cli"
)

var version = "development" // -ldflags=-X main.version=<version from pom.xml>

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	code := app.New(os.Stdin, os.Stdout, os.Stderr, version).Run(ctx, os.Args[1:])
	stop()
	os.Exit(code)
}
