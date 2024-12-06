package main

import (
	"net/http"
	"sync"
	"time"

	"github.com/charmbracelet/log"

	_ "coach/docs"

	httpSwagger "github.com/swaggo/http-swagger"
)

// @title           Coach API
// @version         1.0
// @description     API for the coaching and focus management service
// @BasePath        /

const port = ":8080"

var (
	clientsMux sync.Mutex
	quoteStore QuoteStore
)

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

	http.HandleFunc("/health", healthHandler)
	http.HandleFunc("/focusing", focusHandler)
	http.HandleFunc("/connect", websocketHandler)
	http.Handle("/swagger/", httpSwagger.Handler(
		httpSwagger.URL("/swagger/doc.json"),
	))
	log.Info("Server starting on", "port", port)
	log.Fatal(http.ListenAndServe(port, nil))
}

// @Summary Health check endpoint
// @Description Returns the health status of the API
// @Tags health
// @Produce plain
// @Success 200 {string} string "Healthy"
// @Router /health [get]
func healthHandler(w http.ResponseWriter, r *http.Request) {
	w.WriteHeader(http.StatusOK)
	w.Write([]byte("Healthy"))
}
