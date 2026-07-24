package main

import (
	"flag"
	"fmt"
	"io/fs"
	"net"
	"net/http"
	"time"

	"github.com/charmbracelet/log"

	"coach/admin"
	_ "coach/docs"
	"coach/internal"

	httpSwagger "github.com/swaggo/http-swagger"
)

// @title           Coach API
// @version         1.0
// @description     API for the coaching and focus management service
// @BasePath        /

var port string

func main() {
	// Parse command line flags
	flag.StringVar(&port, "port", "127.0.0.1:8080", "loopback HTTP listen address")
	flag.Parse()
	if err := requireLoopbackAddress(port); err != nil {
		log.Fatal("Invalid listen address", "error", err)
	}

	log.SetTimeFormat(time.Stamp)
	log.SetReportCaller(true)

	// Create and initialize the server
	adminFS, err := fs.Sub(admin.Dist, "dist")
	if err != nil {
		log.Fatalf("Failed to load admin assets: %v", err)
	}

	server, err := coach.NewServer(adminFS)
	if err != nil {
		log.Fatalf("Failed to initialize server: %v", err)
	}
	defer server.Close()

	// Set up routes
	mux := server.SetupRoutes()

	// Add swagger handler
	http.Handle("/", mux)
	http.Handle("/swagger/", httpSwagger.Handler(
		httpSwagger.URL("/swagger/doc.json"),
	))

	httpServer := &http.Server{
		Addr:              port,
		Handler:           nil,
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
	log.Info("Server starting", "address", port)
	log.Fatal(httpServer.ListenAndServe())
}

func requireLoopbackAddress(address string) error {
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		return fmt.Errorf("parse address: %w", err)
	}
	if host == "localhost" {
		return nil
	}
	ip := net.ParseIP(host)
	if ip == nil || !ip.IsLoopback() {
		return fmt.Errorf("address must use an explicit loopback host")
	}
	return nil
}
