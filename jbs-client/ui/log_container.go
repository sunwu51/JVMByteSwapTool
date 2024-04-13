package ui

import (
	"strings"

	"github.com/charmbracelet/bubbles/textarea"
	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"
	"github.com/sunwu51/jbs/client/request"
)

const logMaxLength = 200

type LogContainer struct {
	messages []string
	text     textarea.Model
}

func (m LogContainer) Init() tea.Cmd {
	return nil
}

func (m LogContainer) Update(msg tea.Msg) (LogContainer, tea.Cmd) {
	switch msg := msg.(type) {
	case request.AppendLogMsg:
		m.messages = append([]string{string(msg)}, m.messages...)
		str := strings.Join(m.messages, "\n")
		if len(m.messages) > logMaxLength {
			m.messages = m.messages[0:logMaxLength]
		}
		m.text.SetValue(str)
	case tea.WindowSizeMsg:
		m.text.SetWidth(msg.Width/2 - 10)
	}
	return m, nil
}

func (m LogContainer) View() string {
	st := lipgloss.NewStyle().
		Border(lipgloss.RoundedBorder()).
		BorderForeground(lipgloss.Color("#26f7ce")).
		BorderBackground(lipgloss.Color("#26f7ce")).
		Padding(1)
	return st.Render(m.text.View())
}

func NewLogContainer() LogContainer {
	text := textarea.New()
	text.SetHeight(34)
	text.ShowLineNumbers = false
	text.Prompt = ""
	text.Blur()
	text.CharLimit = -1
	return LogContainer{
		messages: []string{},
		text:     text,
	}
}
