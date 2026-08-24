package plugincontract

import "{{pluginId}}/fengyu"

const MethodHello = "hello"

type HelloInput struct {
	Name string `json:"name" description:"Name to greet."`
}

type HelloOutput struct {
	Message string `json:"message" description:"Rendered greeting."`
}

var Contract = fengyu.NewContract("{{pluginId}}").RPC(
	MethodHello,
	"Echo a greeting back to the UI.",
	HelloInput{},
	HelloOutput{},
	"worker/plugincontract/contract.go",
)
