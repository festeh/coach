package main

import (
	"net/http"
	"time"

	"github.com/charmbracelet/log"

	_ "coach/docs"
	"coach/internal"

	httpSwagger "github.com/swaggo/http-swagger"
)

// @title           Coach API
// @version         1.0
// @description     API for the coaching and focus management service
// @BasePath        /

const port = ":8080"

func main() {
	log.SetTimeFormat(time.Stamp)
	log.SetReportCaller(true)

	// Create and initialize the server
	server, err := coach.NewServer()
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

