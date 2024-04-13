package request

import (
	"encoding/json"
	"fmt"
	"io/ioutil"
	"log"
	"net/http"
	"time"

	"github.com/gorilla/websocket"
)

type AppendLogMsg string

var ws *websocket.Conn
var Host string
var WsPort int
var HttpPort int

func ConnectWebSocket(updateMsgChan chan<- AppendLogMsg) {
	dial := websocket.Dialer{}
	c, _, err := dial.Dial(fmt.Sprintf("ws://%s:%d", Host, WsPort), nil)
	ws = c
	if err != nil {
		log.Panic("dial:", err)
	}
	defer c.Close()

	for {
		_, message, err := c.ReadMessage()
		if err != nil {
			log.Fatal("read:", err)
			break
		}
		j := make(map[string]string)
		json.Unmarshal(message, &j)

		updateMsgChan <- AppendLogMsg(time.Now().Format("[2006-01-02 15:04:05]") + " " + j["content"])
	}
}

func SendMessage(msg string) error {
	return ws.WriteMessage(websocket.TextMessage, []byte(msg))
}

func Reset() string {
	url := fmt.Sprintf("http://%s:%d/reset", Host, HttpPort)
	resp, err := http.Get(url)
	if err != nil {
		log.Panic(err)
	}
	defer resp.Body.Close()
	body, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		log.Panic(err)
	}

	return string(body)
}
