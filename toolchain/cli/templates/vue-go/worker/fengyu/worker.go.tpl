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
type Handler func(*CallContext, map[string]any) (any, error)
type CallContext struct { context.Context; ID, PluginID, PluginRoot, Locale string }
type RPCError struct { Code, Message string }
func (e *RPCError) Error() string { return e.Message }
type pendingCall struct { cancel context.CancelFunc }
type Worker struct { handlers map[string]Handler; pending map[string]*pendingCall; pendingMu, writeMu sync.Mutex; pluginID, pluginRoot string }
func New() *Worker { return &Worker{handlers:map[string]Handler{}, pending:map[string]*pendingCall{}} }
func (w *Worker) On(method string, handler Handler) *Worker {
	if method == "" || strings.HasPrefix(method,"$/fengyu/") || handler == nil { panic("method and handler required; reserved namespace is not allowed") }
	if _, ok := w.handlers[method]; ok { panic("duplicate method: "+method) }
	w.handlers[method]=handler; return w
}
func (w *Worker) Run() error { return w.Serve(os.Stdin, os.Stdout) }
func (w *Worker) ServeTCP(host string, port int, pluginID, pluginRoot string) error {
	if host!="127.0.0.1" && host!="::1" && host!="localhost" { return errors.New("development worker must bind to loopback") }
	w.pluginID,w.pluginRoot=pluginID,pluginRoot; home,err:=os.UserHomeDir(); if err!=nil{return err}; bytes:=make([]byte,32); if _,err=rand.Read(bytes);err!=nil{return err}; token:=base64.RawURLEncoding.EncodeToString(bytes)
	dir:=filepath.Join(home,".fengyu"); if err=os.MkdirAll(dir,0o700);err!=nil{return err}; tokenPath:=filepath.Join(dir,fmt.Sprintf("dev-token-%d",port)); if err=os.WriteFile(tokenPath,[]byte(token+"\n"),0o600);err!=nil{return err}; if err=os.Chmod(tokenPath,0o600);err!=nil{return err}
	listener,err:=net.Listen("tcp",net.JoinHostPort(host,fmt.Sprint(port))); if err!=nil{return err}; defer listener.Close()
	for { connection,acceptErr:=listener.Accept(); if acceptErr!=nil{return acceptErr}; go w.serveConnection(connection,token) }
}
func (w *Worker) serveConnection(connection net.Conn,token string){ defer connection.Close(); reader:=bufio.NewReader(connection); auth,err:=reader.ReadString('\n'); if err!=nil || strings.TrimSpace(auth)!="AUTH "+token{return}; _=w.Serve(reader,connection) }
func (w *Worker) Serve(input io.Reader, output io.Writer) error {
	scanner:=bufio.NewScanner(input); scanner.Buffer(make([]byte,65536),16*1024*1024); var calls sync.WaitGroup
	for scanner.Scan() {
		var r request
		if json.Unmarshal(scanner.Bytes(),&r)!=nil { w.write(output,response{JSONRPC:"2.0",Error:rpcError(-32700,"invalid JSON","PARSE_ERROR")}); continue }
		if r.JSONRPC!="2.0" || r.Method=="" { w.write(output,response{JSONRPC:"2.0",Error:rpcError(-32600,"invalid request","INVALID_REQUEST")}); continue }
		if r.Params==nil { r.Params=map[string]any{} }
		switch r.Method {
		case "$/fengyu/initialize": w.initialize(output,r.ID,r.Params)
		case "$/cancelRequest": w.cancel(fmt.Sprint(r.Params["id"]))
		case "$/fengyu/logging/setLevel": if r.ID!=nil { w.write(output,response{JSONRPC:"2.0",ID:r.ID,Result:map[string]any{"level":r.Params["level"]}}) }
		default: calls.Add(1); go func(r request){ defer calls.Done(); w.dispatch(output,r) }(r)
		}
	}
	calls.Wait(); return scanner.Err()
}
func (w *Worker) initialize(out io.Writer,id any,p map[string]any) {
	v,ok:=p["protocolVersion"].(float64); if !ok || int(v)!=1 { w.write(out,response{JSONRPC:"2.0",ID:id,Error:rpcError(-32602,"unsupported protocol","PROTOCOL_MISMATCH")}); return }
	w.write(out,response{JSONRPC:"2.0",ID:id,Result:map[string]any{"protocolVersion":1,"runtime":"go","sdkVersion":"2.0.0","capabilities":[]string{"cancellation","locale","structuredLogs"}}})
}
func (w *Worker) dispatch(out io.Writer,r request) {
	id:=""; if r.ID!=nil { id=fmt.Sprint(r.ID) }; ctx,cancel:=context.WithCancel(context.Background()); call:=&pendingCall{cancel:cancel}
	if id!="" { w.pendingMu.Lock(); if old:=w.pending[id]; old!=nil { old.cancel() }; w.pending[id]=call; w.pendingMu.Unlock() }
	defer func(){ cancel(); if id!="" { w.pendingMu.Lock(); if w.pending[id]==call { delete(w.pending,id) }; w.pendingMu.Unlock() } }()
	locale:=""; if r.FengYu!=nil { locale,_=r.FengYu["locale"].(string) }
	h:=w.handlers[r.Method]; if h==nil { w.write(out,response{JSONRPC:"2.0",ID:r.ID,Error:rpcError(-32601,"unknown method: "+r.Method,"METHOD_NOT_FOUND")}); return }
	pluginID,pluginRoot:=w.pluginID,w.pluginRoot; if pluginID==""{pluginID=os.Getenv("FENGYU_PLUGIN_ID")}; if pluginRoot==""{pluginRoot=os.Getenv("FENGYU_PLUGIN_ROOT")}
	result,err:=h(&CallContext{Context:ctx,ID:id,PluginID:pluginID,PluginRoot:pluginRoot,Locale:locale},r.Params)
	if errors.Is(ctx.Err(),context.Canceled) { err=&RPCError{Code:"CANCELLED",Message:"request cancelled"} }
	if err!=nil { code,msg:="INTERNAL_ERROR","handler failed"; var rpc *RPCError; if errors.As(err,&rpc){code,msg=rpc.Code,rpc.Message}; w.write(out,response{JSONRPC:"2.0",ID:r.ID,Error:rpcError(-32000,msg,code)}); return }
	if r.ID!=nil { w.write(out,response{JSONRPC:"2.0",ID:r.ID,Result:result}) }
}
func (w *Worker) cancel(id string){ w.pendingMu.Lock(); call:=w.pending[id]; w.pendingMu.Unlock(); if call!=nil {call.cancel()} }
func (w *Worker) write(out io.Writer,r response){ w.writeMu.Lock(); defer w.writeMu.Unlock(); _=json.NewEncoder(out).Encode(r) }
type request struct { JSONRPC string `json:"jsonrpc"`; ID any `json:"id"`; Method string `json:"method"`; Params map[string]any `json:"params"`; FengYu map[string]any `json:"_fengyu"` }
type response struct { JSONRPC string `json:"jsonrpc"`; ID any `json:"id,omitempty"`; Result any `json:"result,omitempty"`; Error any `json:"error,omitempty"` }
func rpcError(code int,message,data string) map[string]any { return map[string]any{"code":code,"message":message,"data":map[string]any{"code":data}} }
