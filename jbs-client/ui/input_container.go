package ui

import (
	"fmt"
	"io"
	"strings"

	"github.com/charmbracelet/bubbles/list"
	"github.com/charmbracelet/bubbles/textarea"
	"github.com/charmbracelet/bubbles/textinput"
	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"
	"github.com/sunwu51/jbs/client/request"
)

var (
	focusedStyle  = lipgloss.NewStyle().Foreground(lipgloss.Color("205"))
	blurredStyle  = lipgloss.NewStyle().Foreground(lipgloss.Color("240"))
	focusedButton = focusedStyle.Render("[ Submit ]")
	blurredButton = blurredStyle.Render("[ Submit ]")
)

type InputContainer struct {
	chooseMenu list.Model
	inputMenu  inputs
	level      int
	width      int
}

type inputs struct {
	focusIndex int
	labels     []string
	inputs     []inputModel
}

type inputModel interface {
	Focus() tea.Cmd
	Blur()
	View() string
	Value() string
}
type listItem string

type listItemDelegate struct{}

type chooseCursorMsg int
type gotoMainMenu struct{}

func (i listItem) FilterValue() string { return "" }

func (d listItemDelegate) Height() int                             { return 1 }
func (d listItemDelegate) Spacing() int                            { return 0 }
func (d listItemDelegate) Update(_ tea.Msg, _ *list.Model) tea.Cmd { return nil }
func (d listItemDelegate) Render(w io.Writer, m list.Model, index int, item list.Item) {
	i, ok := item.(listItem)
	if !ok {
		return
	}
	str := "  " + fmt.Sprintf("%d. %s", index+1, i)
	if index == m.Index() {
		str = focusedStyle.Copy().Bold(true).
			Render("> " + fmt.Sprintf("%d. %s", index+1, i))
	}
	fmt.Fprint(w, str)
}

// ========inputs: a custom ui component with multi inputs and labels
func (m inputs) Init() tea.Cmd {
	return func() tea.Msg {
		return chooseCursorMsg(0)
	}
}

func (m inputs) Update(msg tea.Msg) (inputs, tea.Cmd) {
	cmds := make([]tea.Cmd, len(m.inputs))
	switch msg := msg.(type) {
	case tea.KeyMsg:
		switch msg.String() {
		case "esc":
			return m, func() tea.Msg { return gotoMainMenu{} }
		case "tab", "shift+tab":
			if msg.String() == "tab" {
				m.focusIndex = (m.focusIndex + 1) % (len(m.inputs) + 1)
			} else {
				m.focusIndex = (m.focusIndex - 1) % (len(m.inputs) + 1)
			}
			for i, inp := range m.inputs {
				if i == m.focusIndex {
					cmds[i] = inp.Focus()
					m.inputs[i] = inp
				} else {
					inp.Blur()
					m.inputs[i] = inp
				}
			}
			return m, tea.Batch(cmds...)
		}
	}
	return m, m.updateInputs(msg)
}

func (m *inputs) updateInputs(msg tea.Msg) tea.Cmd {
	cmds := make([]tea.Cmd, len(m.inputs))
	for i := range m.inputs {
		inp := m.inputs[i]
		if _, ok := inp.(*textinput.Model); ok {
			_i, _c := inp.(*textinput.Model).Update(msg)
			inp = &_i
			cmds[i] = _c

		} else {
			_i, _c := inp.(*textarea.Model).Update(msg)
			inp = &_i
			cmds[i] = _c
		}
		m.inputs[i] = inp
	}
	return tea.Batch(cmds...)
}

func (m inputs) View() string {
	var b strings.Builder
	for i := range m.inputs {
		b.WriteString(m.labels[i] + "\n")
		if i == m.focusIndex {
			m.inputs[i].Focus()
			if _, ok := m.inputs[i].(*textinput.Model); ok {
				b.WriteString(focusedStyle.Render(m.inputs[i].View()))
			} else {
				area := m.inputs[i].(*textarea.Model)
				area.FocusedStyle = textarea.Style{
					CursorLine: focusedStyle,
					Text:       focusedStyle,
					LineNumber: focusedStyle,
				}
				b.WriteString(m.inputs[i].View())
			}
		} else {
			m.inputs[i].Blur()
			b.WriteString(m.inputs[i].View())
		}
		b.WriteRune('\n')
	}

	button := &blurredButton
	if m.focusIndex == len(m.inputs) {
		button = &focusedButton
	}
	fmt.Fprintf(&b, "\n\n%s\n", *button)
	b.WriteString(lipgloss.NewStyle().Foreground(lipgloss.Color("#0F0")).Render("press esc go back"))
	return b.String()
}

// ======InputContainer: a custom ui component combined by 2 components:
//
//	a choose list list.Model and a inputs, when level=0 choose list is active and showed
//	when level=1 the inputs is active and choose list hides
func (m InputContainer) Init() tea.Cmd {
	return nil
}

func (m InputContainer) Update(msg tea.Msg) (InputContainer, tea.Cmd) {
	var cmd tea.Cmd
	if m.level == 0 {
		switch msg := msg.(type) {
		case tea.WindowSizeMsg:
			m.width = msg.Width
		case tea.KeyMsg:
			switch msg.Type {
			case tea.KeyTab:
				if m.chooseMenu.Cursor() == len(menu)-1 {
					m.chooseMenu.Select(0)
				} else {
					m.chooseMenu.CursorDown()
				}
			case tea.KeyShiftTab:
				if m.chooseMenu.Cursor() == 0 {
					m.chooseMenu.Select(len(menu) - 1)
				} else {
					m.chooseMenu.CursorUp()
				}
			case tea.KeyEnter:
				m.level = 1
				m.inputMenu.focusIndex = 0
				params := menu[int(m.chooseMenu.Cursor())].Params
				inputs := make([]inputModel, len(params))
				labels := make([]string, len(params))
				for i := range inputs {
					labels[i] = params[i].Name
					if params[i].InputType == textareaType {
						t := textarea.New()
						t.SetWidth(m.width/2 - 1)
						t.SetHeight(25)
						if i == 0 {
							t.Focus()
						}
						t.SetValue(params[i].Value)
						inputs[i] = &t
					} else if params[i].InputType == textinputType {
						t := textinput.New()
						if i == 0 {
							t.Focus()
						}
						t.SetValue(params[i].Value)
						inputs[i] = &t
					}
				}
				m.inputMenu.inputs = inputs
				m.inputMenu.labels = labels
				return m, nil

			}
		}
		return m, func() tea.Msg {
			return chooseCursorMsg(m.chooseMenu.Cursor())
		}
	} else {
		switch msg := msg.(type) {
		case tea.KeyMsg:
			// submit enter
			if m.inputMenu.focusIndex == len(m.inputMenu.inputs) && msg.Type == tea.KeyEnter {
				vals := []string{}
				for _, inp := range m.inputMenu.inputs {
					vals = append(vals, inp.Value())
				}
				for i, p := range menu[m.chooseMenu.Cursor()].Params {
					if !p.Checker(m.inputMenu.inputs[i].Value()) {
						return m, func() tea.Msg { return request.AppendLogMsg("Param Invalid") }
					}
				}
				request.SendMessage(menu[m.chooseMenu.Cursor()].ToJSON(vals))
			}
		case gotoMainMenu:
			m.level = 0
		}
		m.inputMenu, cmd = m.inputMenu.Update(msg)
	}

	return m, cmd
}

func (m InputContainer) View() string {
	if m.level == 0 {
		return m.chooseMenu.View()
	}
	return m.inputMenu.View()
}

func NewInputContainer() InputContainer {
	items := make([]list.Item, 0)
	for _, k := range menu {
		items = append(items, listItem(k.Name))
	}
	chooseMenu := list.New(items, listItemDelegate{}, 30, 14)
	chooseMenu.Title = "Input the action?"
	chooseMenu.Styles.Title = focusedStyle
	chooseMenu.SetShowHelp(false)
	chooseMenu.SetShowStatusBar(false)
	chooseMenu.SetFilteringEnabled(false)
	return InputContainer{
		level:      0,
		chooseMenu: chooseMenu,
	}
}
