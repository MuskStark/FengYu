package fengyu

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestHandshakeAndHandler(t *testing.T) {
	input := strings.NewReader(
		`{"jsonrpc":"2.0","id":"init","method":"$/fengyu/initialize","params":{"protocolVersion":1}}` + "\n" +
			`{"jsonrpc":"2.0","id":"1","method":"hello","params":{"name":"Ada"}}` + "\n")
	var output bytes.Buffer
	err := New().On("hello", func(_ *CallContext, params map[string]any) (any, error) {
		return map[string]any{"message": "Hello, " + params["name"].(string)}, nil
	}).Serve(input, &output)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(output.String(), `"runtime":"go"`) || !strings.Contains(output.String(), `"message":"Hello, Ada"`) {
		t.Fatalf("unexpected output: %s", output.String())
	}
}

func TestAuthenticatedTCPDevelopmentLoop(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	probe, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := probe.Addr().(*net.TCPAddr).Port
	probe.Close()
	worker := New().On("hello", func(ctx *CallContext, params map[string]any) (any, error) {
		return map[string]any{"message": "Hello, " + params["name"].(string), "pluginId": ctx.PluginID}, nil
	})
	go func() { _ = worker.ServeTCP("127.0.0.1", port, "com.example.go", "/plugin") }()
	tokenPath := filepath.Join(home, ".fengyu", fmt.Sprintf("dev-token-%d", port))
	var token []byte
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		token, err = os.ReadFile(tokenPath)
		if err == nil {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if err != nil {
		t.Fatal(err)
	}
	var connection net.Conn
	for time.Now().Before(deadline) {
		connection, err = net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), 200*time.Millisecond)
		if err == nil {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	fmt.Fprintf(connection, "AUTH %s\n", strings.TrimSpace(string(token)))
	fmt.Fprintln(connection, `{"jsonrpc":"2.0","id":"1","method":"hello","params":{"name":"Ada"}}`)
	var response map[string]any
	if err = json.Unmarshal(mustReadLine(t, connection), &response); err != nil {
		t.Fatal(err)
	}
	result := response["result"].(map[string]any)
	if result["message"] != "Hello, Ada" || result["pluginId"] != "com.example.go" {
		t.Fatalf("unexpected result: %#v", result)
	}
	info, err := os.Stat(tokenPath)
	if err != nil || info.Mode().Perm() != 0o600 {
		t.Fatalf("token permissions: %v %v", info, err)
	}
}

func mustReadLine(t *testing.T, connection net.Conn) []byte {
	t.Helper()
	line, err := bufio.NewReader(connection).ReadBytes('\n')
	if err != nil {
		t.Fatal(err)
	}
	return line
}

func TestTypedContractGeneratesJSONSchema(t *testing.T) {
	type Input struct {
		Name  string `json:"name" description:"Name to greet."`
		Count int    `json:"count,omitempty" default:"2"`
	}
	type Output struct {
		Message string `json:"message"`
	}
	method := NewContract("com.example.go").RPC("hello", "Greeting", Input{}, Output{}, "worker/contract.go").IR()["rpc"].(map[string]any)["methods"].(map[string]any)["hello"].(map[string]any)
	input := method["inputSchema"].(map[string]any)
	properties := input["properties"].(map[string]any)
	if properties["name"].(map[string]any)["description"] != "Name to greet." {
		t.Fatal(properties)
	}
	if properties["count"].(map[string]any)["default"] != float64(2) {
		t.Fatal(properties)
	}
}
