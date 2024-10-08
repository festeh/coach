package main

import (
	"fmt"
	"log"
	"net/http"
	"sync"
)

const port = ":8080"

var (
	clientsMux sync.Mutex
	quoteStore QuoteStore
)

func main() {
	err := state.Load()
	if err != nil {
		log.Fatalf("Failed to load state: %v", err)
	}

	err = quoteStore.Load()
	if err != nil {
		log.Fatalf("Failed to load quotes: %v", err)
	}

	http.HandleFunc("/health", healthHandler)
	http.HandleFunc("/focus", focusHandler)
	http.HandleFunc("/connect", websocketHandler)
	fmt.Printf("Server starting on port %s\n", port)
	log.Fatal(http.ListenAndServe(port, nil))
}

func healthHandler(w http.ResponseWriter, r *http.Request) {
	w.WriteHeader(http.StatusOK)
	w.Write([]byte("Healthy"))
}
