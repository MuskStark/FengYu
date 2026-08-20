// Package fengyu provides the JSON-RPC Worker runtime for Go plugins.
package fengyu

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
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
	handlers map[string]Handler
	pending   map[string]*pendingCall
	pendingMu sync.Mutex
	writeMu sync.Mutex
}

func New() *Worker {
	return &Worker{handlers: map[string]Handler{}, pending: map[string]*pendingCall{}}
}

func (w *Worker) On(method string, handler Handler) *Worker {
	if method == "" || strings.HasPrefix(method, "$/fengyu/") || handler == nil {
		panic("method and handler are required and method must not use the reserved $/fengyu/ namespace")
	}
	if _, exists := w.handlers[method]; exists { panic("duplicate method: " + method) }
	w.handlers[method] = handler
	return w
}

func (w *Worker) Run() error { return w.Serve(os.Stdin, os.Stdout) }

func (w *Worker) Serve(input io.Reader, output io.Writer) error {
	scanner := bufio.NewScanner(input)
	scanner.Buffer(make([]byte, 64*1024), 16*1024*1024)
	var calls sync.WaitGroup
	for scanner.Scan() {
		var request request
		if err := json.Unmarshal(scanner.Bytes(), &request); err != nil {
			w.write(output, response{JSONRPC: "2.0", Error: rpcError(-32700, "invalid JSON", "PARSE_ERROR")})
			continue
		}
		if request.JSONRPC != "2.0" || request.Method == "" {
			w.write(output, response{JSONRPC: "2.0", Error: rpcError(-32600, "invalid JSON-RPC 2.0 request", "INVALID_REQUEST")})
			continue
		}
		params := request.Params
		if params == nil { params = map[string]any{} }
		switch request.Method {
		case initializeMethod:
			w.initialize(output, request.ID, params)
		case cancelMethod:
			w.cancel(fmt.Sprint(params["id"]))
		case setLogLevelMethod:
			if request.ID != nil { w.write(output, response{JSONRPC: "2.0", ID: request.ID, Result: map[string]any{"level": params["level"]}}) }
		default:
			calls.Add(1)
			go func(r request, p map[string]any) { defer calls.Done(); w.dispatch(output, r, p) }(request, params)
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
	if r.FengYu != nil { locale, _ = r.FengYu["locale"].(string) }
	handler := w.handlers[r.Method]
	if handler == nil {
		w.write(output, response{JSONRPC: "2.0", ID: r.ID, Error: rpcError(-32601, "unknown method: "+r.Method, "METHOD_NOT_FOUND")})
		return
	}
	result, err := handler(&CallContext{Context: ctx, ID: id, PluginID: os.Getenv("FENGYU_PLUGIN_ID"), PluginRoot: os.Getenv("FENGYU_PLUGIN_ROOT"), Locale: locale}, params)
	if errors.Is(ctx.Err(), context.Canceled) {
		err = &RPCError{Code: "CANCELLED", Message: "request cancelled"}
	}
	if err != nil {
		code := "INTERNAL_ERROR"
		message := "handler failed"
		var rpc *RPCError
		if errors.As(err, &rpc) { code, message = rpc.Code, rpc.Message }
		w.write(output, response{JSONRPC: "2.0", ID: r.ID, Error: rpcError(-32000, message, code)})
		return
	}
	if r.ID != nil { w.write(output, response{JSONRPC: "2.0", ID: r.ID, Result: result}) }
}

func (w *Worker) cancel(id string) {
	w.pendingMu.Lock()
	call := w.pending[id]
	w.pendingMu.Unlock()
	if call != nil { call.cancel() }
}

func (w *Worker) write(output io.Writer, value response) {
	w.writeMu.Lock()
	defer w.writeMu.Unlock()
	_ = json.NewEncoder(output).Encode(value)
}

type request struct {
	JSONRPC string `json:"jsonrpc"`
	ID any `json:"id"`
	Method string `json:"method"`
	Params map[string]any `json:"params"`
	FengYu map[string]any `json:"_fengyu"`
}
type response struct {
	JSONRPC string `json:"jsonrpc"`
	ID any `json:"id,omitempty"`
	Result any `json:"result,omitempty"`
	Error any `json:"error,omitempty"`
}
func rpcError(code int, message, dataCode string) map[string]any {
	return map[string]any{"code": code, "message": message, "data": map[string]any{"code": dataCode}}
}
