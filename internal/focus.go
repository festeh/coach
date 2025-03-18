package coach

import (
	"encoding/json"
	"github.com/charmbracelet/log"
	"net/http"
	"strconv"
	"time"
)


func broadcastFocusState() {
	message := GetFocusInfo(&state.internal)
	jsonMessage, err := json.Marshal(message)
	if err != nil {
		log.Printf("Error marshaling focus state: %v", err)
		return
	}

	state.BroadcastToClients(jsonMessage)
}
