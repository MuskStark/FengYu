package main

import (
	"fmt"

	"{{pluginId}}/fengyu"
)

func main() {
	err := fengyu.New().On("hello", func(_ *fengyu.CallContext, params map[string]any) (any, error) {
		return map[string]any{"message": "Hello, " + fmt.Sprint(params["name"])}, nil
	}).Run()
	if err != nil { panic(err) }
}
