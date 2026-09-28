package main

import (
	"fmt"
	"os"
	"path/filepath"

	"{{pluginId}}/fengyu"
	"{{pluginId}}/plugincontract"
)

func createWorker() *fengyu.Worker {
	return fengyu.New().On(plugincontract.MethodHello, func(_ *fengyu.CallContext, params map[string]any) (any, error) {
		return map[string]any{"message": "Hello, " + fmt.Sprint(params["name"])}, nil
	})
}

func main() {
	worker := createWorker()
	var err error
	if len(os.Args) > 1 && os.Args[1] == "--dev" {
		root, rootErr := filepath.Abs("..")
		if rootErr != nil { panic(rootErr) }
		err = worker.ServeTCP("127.0.0.1", 24057, "{{pluginId}}", root)
	} else {
		err = worker.Run()
	}
	if err != nil { panic(err) }
}
