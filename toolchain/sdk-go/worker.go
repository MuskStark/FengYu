// Package fengyu provides the JSON-RPC Worker runtime for Go plugins.
package fengyu

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

const ProtocolVersion = 1
const initializeMethod = "$/fengyu/initialize"
const cancelMethod = "$/cancelRequest"
const setLogLevelMethod = "$/fengyu/logging/setLevel"

type Handler func(*CallContext, map[string]any) (any, error)

type CallContext struct {
	context.Context
	ID, PluginID, PluginRoot, Locale string
}

type RPCError struct {
	Code, Message string
}

func (e *RPCError) Error() string { return e.Message }

type pendingCall struct {
	cancel context.CancelFunc
}

type Worker struct {
	handlers   map[string]Handler
	pending    map[string]*pendingCall
	pendingMu  sync.Mutex
	writeMu    sync.Mutex
	pluginID   string
	pluginRoot string
}

func New() *Worker {
	return &Worker{handlers: map[string]Handler{}, pending: map[string]*pendingCall{}}
}

func (w *Worker) On(method string, handler Handler) *Worker {
	if method == "" || strings.HasPrefix(method, "$/fengyu/") || handler == nil {
		panic("method and handler are required and method must not use the reserved $/fengyu/ namespace")
	}
	if _, exists := w.handlers[method]; exists {
		panic("duplicate method: " + method)
	}
	w.handlers[method] = handler
	return w
}

func (w *Worker) Run() error { return w.Serve(os.Stdin, os.Stdout) }

// ServeTCP exposes the authenticated loopback protocol used by fengyu dev and IDE debugging.
func (w *Worker) ServeTCP(host string, port int, pluginID, pluginRoot string) error {
	if host != "127.0.0.1" && host != "::1" && host != "localhost" {
		return errors.New("the FengYu development worker must bind to loopback")
	}
	w.pluginID, w.pluginRoot = pluginID, pluginRoot
	home, err := os.UserHomeDir()
	if err != nil {
		return err
	}
	tokenBytes := make([]byte, 32)
	if _, err = rand.Read(tokenBytes); err != nil {
		return err
	}
	token := base64.RawURLEncoding.EncodeToString(tokenBytes)
	tokenDir := filepath.Join(home, ".fengyu")
	if err = os.MkdirAll(tokenDir, 0o700); err != nil {
		return err
	}
	if err = os.WriteFile(filepath.Join(tokenDir, fmt.Sprintf("dev-token-%d", port)), []byte(token+"\n"), 0o600); err != nil {
		return err
	}
	if err = os.Chmod(filepath.Join(tokenDir, fmt.Sprintf("dev-token-%d", port)), 0o600); err != nil {
		return err
	}
	listener, err := net.Listen("tcp", net.JoinHostPort(host, fmt.Sprint(port)))
	if err != nil {
		return err
	}
	defer listener.Close()
	for {
		connection, acceptErr := listener.Accept()
		if acceptErr != nil {
			return acceptErr
		}
		go w.serveConnection(connection, token)
	}
}

func (w *Worker) serveConnection(connection net.Conn, token string) {
	defer connection.Close()
	reader := bufio.NewReader(connection)
	auth, err := reader.ReadString('\n')
	if err != nil || strings.TrimSpace(auth) != "AUTH "+token {
		return
	}
	_ = w.Serve(reader, connection)
}

func (w *Worker) Serve(input io.Reader, output io.Writer) error {
	scanner := bufio.NewScanner(input)
	scanner.Buffer(make([]byte, 64*1024), 16*1024*1024)
	var calls sync.WaitGroup
	for scanner.Scan() {
		var incoming request
		if err := json.Unmarshal(scanner.Bytes(), &incoming); err != nil {
			w.write(output, response{JSONRPC: "2.0", Error: rpcError(-32700, "invalid JSON", "PARSE_ERROR")})
			continue
		}
		if incoming.JSONRPC != "2.0" || incoming.Method == "" {
			w.write(output, response{JSONRPC: "2.0", Error: rpcError(-32600, "invalid JSON-RPC 2.0 request", "INVALID_REQUEST")})
			continue
		}
		params := incoming.Params
		if params == nil {
			params = map[string]any{}
		}
		switch incoming.Method {
		case initializeMethod:
			w.initialize(output, incoming.ID, params)
		case cancelMethod:
			w.cancel(fmt.Sprint(params["id"]))
		case setLogLevelMethod:
			if incoming.ID != nil {
				w.write(output, response{JSONRPC: "2.0", ID: incoming.ID, Result: map[string]any{"level": params["level"]}})
			}
		default:
			calls.Add(1)
			go func(r request, p map[string]any) { defer calls.Done(); w.dispatch(output, r, p) }(incoming, params)
		}
	}
	calls.Wait()
	return scanner.Err()
}

func (w *Worker) initialize(output io.Writer, id any, params map[string]any) {
	version, ok := params["protocolVersion"].(float64)
	if !ok || int(version) != ProtocolVersion {
		w.write(output, response{JSONRPC: "2.0", ID: id, Error: rpcError(-32602, "unsupported FengYu worker protocol", "PROTOCOL_MISMATCH")})
		return
	}
	w.write(output, response{JSONRPC: "2.0", ID: id, Result: map[string]any{
		"protocolVersion": ProtocolVersion, "runtime": "go", "sdkVersion": "2.0.0",
		"capabilities": []string{"cancellation", "locale", "structuredLogs"},
	}})
}

func (w *Worker) dispatch(output io.Writer, r request, params map[string]any) {
	id := ""
	if r.ID != nil {
		id = fmt.Sprint(r.ID)
	}
	ctx, cancel := context.WithCancel(context.Background())
	call := &pendingCall{cancel: cancel}
	if id != "" {
		w.pendingMu.Lock()
		if previous := w.pending[id]; previous != nil {
			previous.cancel()
		}
		w.pending[id] = call
		w.pendingMu.Unlock()
	}
	defer func() {
		cancel()
		if id != "" {
			w.pendingMu.Lock()
			if w.pending[id] == call {
				delete(w.pending, id)
			}
			w.pendingMu.Unlock()
		}
	}()
	locale := ""
	if r.FengYu != nil {
		locale, _ = r.FengYu["locale"].(string)
	}
	handler := w.handlers[r.Method]
	if handler == nil {
		w.write(output, response{JSONRPC: "2.0", ID: r.ID, Error: rpcError(-32601, "unknown method: "+r.Method, "METHOD_NOT_FOUND")})
		return
	}
	pluginID, pluginRoot := w.pluginID, w.pluginRoot
	if pluginID == "" {
		pluginID = os.Getenv("FENGYU_PLUGIN_ID")
	}
	if pluginRoot == "" {
		pluginRoot = os.Getenv("FENGYU_PLUGIN_ROOT")
	}
	result, err := handler(&CallContext{Context: ctx, ID: id, PluginID: pluginID, PluginRoot: pluginRoot, Locale: locale}, params)
	if errors.Is(ctx.Err(), context.Canceled) {
		err = &RPCError{Code: "CANCELLED", Message: "request cancelled"}
	}
	if err != nil {
		code := "INTERNAL_ERROR"
		message := "handler failed"
		var rpc *RPCError
		if errors.As(err, &rpc) {
			code, message = rpc.Code, rpc.Message
		}
		w.write(output, response{JSONRPC: "2.0", ID: r.ID, Error: rpcError(-32000, message, code)})
		return
	}
	if r.ID != nil {
		w.write(output, response{JSONRPC: "2.0", ID: r.ID, Result: result})
	}
}

func (w *Worker) cancel(id string) {
	w.pendingMu.Lock()
	call := w.pending[id]
	w.pendingMu.Unlock()
	if call != nil {
		call.cancel()
	}
}

func (w *Worker) write(output io.Writer, value response) {
	w.writeMu.Lock()
	defer w.writeMu.Unlock()
	_ = json.NewEncoder(output).Encode(value)
}

type request struct {
	JSONRPC string         `json:"jsonrpc"`
	ID      any            `json:"id"`
	Method  string         `json:"method"`
	Params  map[string]any `json:"params"`
	FengYu  map[string]any `json:"_fengyu"`
}
type response struct {
	JSONRPC string `json:"jsonrpc"`
	ID      any    `json:"id,omitempty"`
	Result  any    `json:"result,omitempty"`
	Error   any    `json:"error,omitempty"`
}

func rpcError(code int, message, dataCode string) map[string]any {
	return map[string]any{"code": code, "message": message, "data": map[string]any{"code": dataCode}}
}
