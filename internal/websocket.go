package coach

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
