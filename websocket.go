package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"

	"github.com/gorilla/websocket"
)

var upgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	CheckOrigin: func(r *http.Request) bool {
		return true
	},
}

func websocketHandler(w http.ResponseWriter, r *http.Request) {
	log.Println("Client connected")
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Println(err)
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

	state.BroadcastToClients(jsonMessage)
}
