package main

import (
	"flag"
	"fmt"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"
	"github.com/sunwu51/jbs/client/request"
	"github.com/sunwu51/jbs/client/ui"
)

type Model struct {
	state          int
	width          int
	height         int
	inputContainer ui.InputContainer
	logContainer   ui.LogContainer
}

func (m Model) Init() tea.Cmd {
	return nil
}

func (m Model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch x := msg.(type) {
	case tea.KeyMsg:
		if x.Type == tea.KeyCtrlC {
			return m, tea.Quit
		}
	case tea.WindowSizeMsg:
		m.width, m.height = x.Width, x.Height
		ready := 1
		m.state = ready
	}
	i, cmd1 := m.inputContainer.Update(msg)
	l, cmd2 := m.logContainer.Update(msg)
	m.inputContainer = i
	m.logContainer = l
	return m, tea.Batch(cmd1, cmd2)
}

func (m Model) View() string {
	if m.width < 100 || m.height < 30 {
		return fmt.Sprintf("Window need to larger than 100x30, current=%dx%d", m.width, m.height)
	}
	initializing := 0
	if m.state == initializing {
		return "Initializing..."
	}
	iv := lipgloss.NewStyle().Width(m.width / 2).Render(
		m.inputContainer.View())
	lv := m.logContainer.View()
	return lipgloss.JoinHorizontal(lipgloss.Bottom, iv, lv)
}

func main() {
	h := flag.String("host", "localhost", "server host")
	port1 := flag.Int("http_port", 8000, "http port")
	port2 := flag.Int("ws_port", 18000, "ws port")
	flag.Parse()
	request.Host = *h
	request.HttpPort = *port1
	request.WsPort = *port2
	p := tea.NewProgram(Model{
		inputContainer: ui.NewInputContainer(),
		logContainer:   ui.NewLogContainer(),
	})
	updateMsgChan := make(chan request.AppendLogMsg)
	go request.ConnectWebSocket(updateMsgChan)
	go func() {
		for msg := range updateMsgChan {
			p.Send(msg)
		}
	}()
	p.Run()
}
