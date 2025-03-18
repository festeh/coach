package main

import (
	"net/http"
	"sync"
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

var (
	clientsMux sync.Mutex
	quoteStore coach.QuoteStore
)

var state = &coach.State{}

func main() {
	log.SetTimeFormat(time.Stamp)
  log.SetReportCaller(true)
	err := state.Load()
	if err != nil {
		log.Fatalf("Failed to load state: %v", err)
	}

	err = quoteStore.Load()
	if err != nil {
		log.Fatalf("Failed to load quotes: %v", err)
	}

	http.HandleFunc("/health", coach.HealthHandler)
	http.HandleFunc("/focusing", coach.FocusHandler)
	http.HandleFunc("/connect", coach.WebsocketHandler)
	http.Handle("/swagger/", httpSwagger.Handler(
		httpSwagger.URL("/swagger/doc.json"),
	))
	log.Info("Server starting on", "port", port)
	log.Fatal(http.ListenAndServe(port, nil))
}

