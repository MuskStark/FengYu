package fengyu

import (
	"bytes"
	"strings"
	"testing"
)

func TestHandshakeAndHandler(t *testing.T) {
	input := strings.NewReader(
		`{"jsonrpc":"2.0","id":"init","method":"$/fengyu/initialize","params":{"protocolVersion":1}}` + "\n" +
		`{"jsonrpc":"2.0","id":"1","method":"hello","params":{"name":"Ada"}}` + "\n")
	var output bytes.Buffer
	err := New().On("hello", func(_ *CallContext, params map[string]any) (any, error) {
		return map[string]any{"message": "Hello, " + params["name"].(string)}, nil
	}).Serve(input, &output)
	if err != nil { t.Fatal(err) }
	if !strings.Contains(output.String(), `"runtime":"go"`) || !strings.Contains(output.String(), `"message":"Hello, Ada"`) {
		t.Fatalf("unexpected output: %s", output.String())
	}
}
