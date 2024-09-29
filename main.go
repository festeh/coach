package main

import (
	"fmt"
	"log"
	"net/http"
)

const port = ":8080"

func main() {
	http.HandleFunc("/health", healthHandler)
	fmt.Printf("Server starting on port %s\n", port)
	log.Fatal(http.ListenAndServe(port, nil))
}

func healthHandler(w http.ResponseWriter, r *http.Request) {
	w.WriteHeader(http.StatusOK)
	w.Write([]byte("OK"))
}
