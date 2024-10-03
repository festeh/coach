package main

import (
	"fmt"
	"log"
	"net/http"

	"github.com/gorilla/websocket"
)

const port = ":8080"

var upgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
}

func main() {
	err := state.Load()
	if err != nil {
		log.Fatalf("Failed to load state: %v", err)
	}

	http.HandleFunc("/health", healthHandler)
	http.HandleFunc("/focus", focusHandler)
	http.HandleFunc("/connect", websocketHandler)
	fmt.Printf("Server starting on port %s\n", port)
	log.Fatal(http.ListenAndServe(port, nil))
}

func websocketHandler(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Println(err)
		return
	}
	defer conn.Close()

	for {
		messageType, p, err := conn.ReadMessage()
		if err != nil {
			log.Println(err)
			return
		}
		if string(p) == "ping" {
			if err := conn.WriteMessage(messageType, []byte("pong")); err != nil {
				log.Println(err)
				return
			}
		}
	}
}

func healthHandler(w http.ResponseWriter, r *http.Request) {
	w.WriteHeader(http.StatusOK)
	w.Write([]byte("Healthy"))
}

func focusHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	if r.Method == http.MethodGet {
		w.WriteHeader(http.StatusOK)
		if state.IsFocusing() {
			w.Write([]byte("Focusing"))
		} else {
			w.Write([]byte("Not focusing"))
		}
		return
	}

	// POST method
	err := r.ParseForm()
	if err != nil {
		http.Error(w, "Failed to parse form", http.StatusBadRequest)
		return
	}

	focus := r.FormValue("focus") == "true"
	err = state.SetFocusing(focus)
	if err != nil {
		http.Error(w, "Failed to set focus state", http.StatusInternalServerError)
		return
	}

	w.WriteHeader(http.StatusOK)
	if focus {
		w.Write([]byte("Now focusing"))
	} else {
		w.Write([]byte("No longer focusing"))
	}
}
