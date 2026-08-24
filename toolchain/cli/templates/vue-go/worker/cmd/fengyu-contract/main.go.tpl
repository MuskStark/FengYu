package main

import (
	"path/filepath"

	"{{pluginId}}/plugincontract"
)

func main() {
	root, err := filepath.Abs("..")
	if err != nil { panic(err) }
	if err = plugincontract.Contract.Write(root); err != nil { panic(err) }
}
