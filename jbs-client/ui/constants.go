package ui

import (
	"encoding/json"
	"math/rand"
	"strconv"
	"strings"
	"time"

	"github.com/thoas/go-funk"
)

const (
	textinputType = 0
	textareaType  = 1
)

var (
	menu = make([]Function, 0)
)

type ParamChecker interface {
	Check(string) bool
}

type Function struct {
	Name   string
	Params []struct {
		Name      string
		InputType int
		Checker   func(string) bool
		Value     string
	}
	ToJSON func([]string) string
}

func ClassAndMethodChecker(s string) bool { return len(strings.Split(s, "#")) == 2 }

func CommonMap() map[string]interface{} {
	m := make(map[string]interface{})
	m["id"] = randomString(4)
	m["timestamp"] = time.Now().UnixMilli()
	return m
}

func WatchToJSON(params []string) string {
	m := CommonMap()
	m["type"] = "WATCH"
	m["signature"] = params[0]
	minCost, _ := strconv.Atoi(params[1])
	m["minCost"] = minCost
	str, _ := json.Marshal(m)
	return string(str)
}

func OuterWatchToJSON(params []string) string {
	m := CommonMap()
	m["type"] = "WATCH"
	m["signature"] = params[0]
	m["innerSignature"] = params[1]
	str, _ := json.Marshal(m)
	return string(str)
}

func TraceToJSON(params []string) string {
	m := CommonMap()
	m["type"] = "TRACE"
	m["signature"] = params[0]
	minCost, _ := strconv.Atoi(params[1])
	m["minCost"] = minCost
	str, _ := json.Marshal(m)
	return string(str)
}

func ChangeBodyToJSON(params []string) string {
	m := CommonMap()
	m["type"] = "CHANGE_BODY"
	m["className"] = strings.Split(params[0], "#")[0]
	m["method"] = strings.Split(params[0], "#")[1]
	m["paramTypes"] = funk.Map(params[1], func(s string) string {
		return strings.TrimSpace(s)
	}).([]string)
	m["body"] = params[2]
	str, _ := json.Marshal(m)
	return string(str)
}

func ChangeResultToJSON(params []string) string {
	m := CommonMap()
	m["type"] = "CHANGE_BODY"
	m["className"] = strings.Split(params[0], "#")[0]
	m["method"] = strings.Split(params[0], "#")[1]
	m["paramTypes"] = funk.Map(params[1], func(s string) string {
		return strings.TrimSpace(s)
	}).([]string)
	m["innerClassName"] = strings.Split(params[2], "#")[0]
	m["innerMethod"] = strings.Split(params[2], "#")[1]
	m["body"] = params[3]
	str, _ := json.Marshal(m)
	return string(str)
}

func ExecToJSON(params []string) string {
	m := CommonMap()
	m["body"] = params[0]
	m["type"] = "EXEC"
	str, _ := json.Marshal(m)
	return string(str)
}

func init() {
	rand.Seed(time.Now().UnixNano())
	watch := Function{"Watch", []struct {
		Name      string
		InputType int
		Checker   func(s string) bool
		Value     string
	}{
		{"ClassName#MethodName", 0, ClassAndMethodChecker, ""},
		{"MinCost", 0, func(s string) bool { return true }, "0"},
	}, WatchToJSON}

	outerWatch := Function{"OuterWatch", []struct {
		Name      string
		InputType int
		Checker   func(s string) bool
		Value     string
	}{
		{"ClassName#MethodName", 0, ClassAndMethodChecker, ""},
		{"InnerClassName#InnerMethodName", 0, ClassAndMethodChecker, ""},
	}, OuterWatchToJSON}

	trace := Function{"Trace", []struct {
		Name      string
		InputType int
		Checker   func(s string) bool
		Value     string
	}{
		{"ClassName#MethodName", 0, ClassAndMethodChecker, ""},
		{"MinCost", 0, func(s string) bool { return true }, "0"},
	}, TraceToJSON}

	changeBody := Function{"ChangeBody", []struct {
		Name      string
		InputType int
		Checker   func(s string) bool
		Value     string
	}{
		{"ClassName#MethodName", 0, ClassAndMethodChecker, ""},
		{"ParamTypes", 0, func(s string) bool { return true }, ""},
		{"Body", 1, func(s string) bool { return true }, ""},
	}, ChangeBodyToJSON}

	changeResult := Function{"ChangeResult", []struct {
		Name      string
		InputType int
		Checker   func(s string) bool
		Value     string
	}{
		{"ClassName#MethodName", 0, ClassAndMethodChecker, ""},
		{"ParamTypes", 0, func(s string) bool { return true }, ""},
		{"InnerClassName#InnerMethodName", 0, ClassAndMethodChecker, ""},
		{"Body", 1, func(s string) bool { return true }, ""},
	}, ChangeResultToJSON}

	exec := Function{"Exec", []struct {
		Name      string
		InputType int
		Checker   func(s string) bool
		Value     string
	}{
		{"Body", 1, func(s string) bool { return true }, `{
	try {
		w.Global.info(w.Global.ognl("#root", ctx));
	} catch(Exception e) {
		w.Global.info(e.toString());
	}
}`},
	}, ExecToJSON}

	reset := Function{"Reset", []struct {
		Name      string
		InputType int
		Checker   func(s string) bool
		Value     string
	}{}, func(s []string) string { return "{}" }}

	menu = []Function{watch, outerWatch, trace, changeBody, changeResult, exec, reset}

}

const letterNumberBytes = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

func randomString(n int) string {
	b := make([]byte, n)
	for i := range b {
		b[i] = letterNumberBytes[rand.Intn(len(letterNumberBytes))]
	}
	return string(b)
}
