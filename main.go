package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"sync"

	"github.com/gorilla/websocket"
)

const port = ":8080"

var upgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	CheckOrigin: func(r *http.Request) bool {
		return true
	},
}

var (
	clients    = make(map[*websocket.Conn]bool)
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

func websocketHandler(w http.ResponseWriter, r *http.Request) {
	log.Println("Client connected")
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Println(err)
		return
	}

	clientsMux.Lock()
	clients[conn] = true
	clientsMux.Unlock()

	defer func() {
		conn.Close()
		clientsMux.Lock()
		delete(clients, conn)
		clientsMux.Unlock()
	}()

	for {
		messageType, p, err := conn.ReadMessage()
		fmt.Println(messageType, string(p), err)
		if err != nil {
			log.Println(err)
			return
		}
		if string(p) == "get_quote" {
      broadcastQuote()
		}
	}
}

func broadcastQuote() {
  message := struct {
    Event string `json:"event"`
    Quote string `json:"quote"`
  }{
    Event: "quote",
    Quote: quoteStore.GetQuote().Text,
  }

  jsonMessage, err := json.Marshal(message)
  if err != nil {
    log.Printf("Error marshaling quote: %v", err)
    return
  }

  clientsMux.Lock()
  for client := range clients {
    err := client.WriteMessage(websocket.TextMessage, jsonMessage)
    if err != nil {
      log.Printf("Error sending message to client: %v", err)
      client.Close()
      delete(clients, client)
    }
  }
  clientsMux.Unlock()
}

func broadcastFocusState(focusing bool) {
	message := struct {
		Event string `json:"event"`
		Focus bool   `json:"focus"`
	}{
		Event: "focus",
		Focus: focusing,
	}

	jsonMessage, err := json.Marshal(message)
	if err != nil {
		log.Printf("Error marshaling focus state: %v", err)
		return
	}

	clientsMux.Lock()
	for client := range clients {
		err := client.WriteMessage(websocket.TextMessage, jsonMessage)
		if err != nil {
			log.Printf("Error sending message to client: %v", err)
			client.Close()
			delete(clients, client)
		}
	}
	clientsMux.Unlock()
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

	// Broadcast the new focus state to all connected clients
	go broadcastFocusState(focus)

	w.WriteHeader(http.StatusOK)
	if focus {
		w.Write([]byte("Now focusing"))
	} else {
		w.Write([]byte("No longer focusing"))
	}
}
