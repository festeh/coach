package main

import (
	"encoding/json"
	"fmt"
	"net/http"
  "github.com/charmbracelet/log"

	"github.com/gorilla/websocket"
)

var upgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	CheckOrigin: func(r *http.Request) bool {
		return true
	},
}

// @Summary WebSocket connection endpoint
// @Description Establishes a WebSocket connection for real-time updates
// @Tags websocket
// @Accept json
// @Produce json
// @Success 101 {string} string "Switching Protocols to WebSocket"
// @Failure 400 {string} string "Bad Request"
// @Router /connect [get]
func websocketHandler(w http.ResponseWriter, r *http.Request) {
	log.Info("Client connected")
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Error(err)
		return
	}

	state.AddClient(conn)

	defer func() {
		conn.Close()
		state.RemoveClient(conn)
	}()

	for {
		messageType, p, err := conn.ReadMessage()
		fmt.Println(messageType, string(p), err)
		if err != nil {
			log.Error(err)
			return
		}
		if string(p) == "get_quote" {
			broadcastQuote()
		}
		if string(p) == "get_focusing" {
			broadcastFocusState()
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

	state.BroadcastToClients(jsonMessage)
}
