package main

import (
	"flag"
	"io/fs"
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
	flag.StringVar(&port, "port", ":8080", "HTTP server port (e.g. ':8080')")
	flag.Parse()

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

	// Set up routes
	mux := server.SetupRoutes()

	// Add swagger handler
	http.Handle("/", mux)
	http.Handle("/swagger/", httpSwagger.Handler(
		httpSwagger.URL("/swagger/doc.json"),
	))

	log.Info("Server starting on", "port", port)
	log.Fatal(http.ListenAndServe(port, nil))
}

