package main

import (
	"crypto/tls"
	"math/rand"

	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"path"
	"strings"
	"sync"
	"time"

	"github.com/bugfan/i996-android/tunnel/conn"
)

const (
	tid = "testid"
)

func httpserver() {
	handler := func(w http.ResponseWriter, r *http.Request) {
		// 动态决定目标
		targetURL, err := url.Parse("https://www.json.cn")
		if err != nil {
			http.Error(w, "invalid target", http.StatusInternalServerError)
			return
		}

		transport, err := GetHTTPTransport(tid, "", "")
		if err != nil {
			http.Error(w, "invalid target", http.StatusInternalServerError)
			return
		}

		// 创建反向代理
		proxy := httputil.NewSingleHostReverseProxy(targetURL)

		// 动态修改底层 transport
		proxy.Transport = transport

		// 你也可以在这里改 Header、日志、注入认证信息等
		proxy.ModifyResponse = func(resp *http.Response) error {
			resp.Header.Set("X-Proxy-By", "GoDynamicProxy")
			return nil
		}

		// 转发
		proxy.ServeHTTP(w, r)
	}

	srv := &http.Server{
		Addr:    ":4444",
		Handler: http.HandlerFunc(handler),
	}

	log.Println("Reverse proxy listening on :4444 (→ https://www.json.cn)")
	log.Fatal(srv.ListenAndServe())
}

func main() {

	// http server
	go httpserver()

	// tunnel server
	keyPEM, err := os.ReadFile("cert/key.pem")
	if err != nil {
		fmt.Println("failed to read key.pem:", err)
		return
	}
	certPEM, err := os.ReadFile("cert/cert.pem")
	if err != nil {
		fmt.Println("failed to read cert.pem:", err)
		return
	}
	tlsConf := LoadTLSConfigFromBytes(certPEM, keyPEM)
	if tlsConf != nil {
		tlsConf.ClientSessionCache = tls.NewLRUClientSessionCache(5000)
	}
	ListenTunnel(":3333", tlsConf)
}

type ConnRegistry struct {
	conns map[string]map[string]*conn.FrameConn
	sync.RWMutex
}

func NewConnRegistry() *ConnRegistry {
	return &ConnRegistry{
		conns: make(map[string]map[string]*conn.FrameConn),
	}
}
func (r *ConnRegistry) Get(clientId string) *conn.FrameConn {
	r.RLock()
	defer r.RUnlock()
	if r.conns[clientId] == nil {
		return nil
	}

	length := len(r.conns[clientId])
	if length == 0 {
		return nil
	}
	idx := rand.Int() % length
	i := 0
	for _, f := range r.conns[clientId] {
		if i == idx {
			return f
		}
		i++
	}
	return nil
}
func (r *ConnRegistry) Add(clientId string, ctl *conn.FrameConn) {
	r.Lock()
	defer r.Unlock()
	if r.conns[clientId] == nil {
		r.conns[clientId] = make(map[string]*conn.FrameConn)
	}
	r.conns[clientId][ctl.RemoteAddr().String()] = ctl
	go func() {
		for {
			if ctl.Error() != nil || ctl.RemoteIP() == "" {
				r.Del(clientId, ctl)
				return
			}
			time.Sleep(1 * time.Second)
		}
	}()
	fmt.Printf("tunnels %#v\n", r.conns)
}

func (r *ConnRegistry) Del(clientId string, ctl *conn.FrameConn) error {
	r.Lock()
	defer r.Unlock()
	if r.conns[clientId] == nil {
		return nil
	}
	found := false
	for addr, c := range r.conns[clientId] {
		if c == ctl || addr == ctl.RemoteAddr().String() {
			delete(r.conns[clientId], addr)
			found = true
		}
	}
	if found {
		go ctl.Reset()
	}
	if len(r.conns[clientId]) == 0 {
		delete(r.conns, clientId)
	}
	fmt.Printf("after delete tunnels %#v\n", r.conns)
	return nil
}

func (r *ConnRegistry) IDs() (ids map[string]string) {
	ids = make(map[string]string)
	for id, c := range r.conns {
		ips := make([]string, 0, len(c))
		for _, tunnel := range c {
			ips = append(ips, tunnel.RemoteIP())
		}
		ip := strings.Join(ips, ";")
		if ip == "" {
			ip = fmt.Sprintf("未识别隧道IP[%d]", len(r.conns))
		}
		ids[ip] = id
	}
	return
}

var (
	connRegistry *ConnRegistry
)

func init() {
	connRegistry = NewConnRegistry()
}

func ListenTunnel(addr string, tlsConfig *tls.Config) {
	listen, err := conn.Listen(addr, "tcp", tlsConfig)
	if err != nil {
		panic(err)
	}
	for {
		fc, err := listen.Accept()
		if err != nil {
			fmt.Printf("error accept connection %s", err.Error())
			continue
		}
		fmt.Printf("accepted tunnel\n")
		go func(f *conn.FrameConn) {
			time.Sleep(200 * time.Millisecond)
			fmt.Printf("开始获取 info\n")
			info := f.Info()
			fmt.Printf("获取到 info: %#v\n", info)
			if info == nil {
				fmt.Printf("info 为 nil，关闭连接\n")
				f.Close()
				return
			}
			if info.ID == "" {
				id, _ := SecureRandId(16)
				info.ID = id
				fmt.Printf("no id found in client set id: %s\n", id)
				f.SetInfo(info)
			}
			fmt.Printf("add register id: %s\n", info.ID)
			connRegistry.Add(info.ID, f)
			for {
				time.Sleep(1 * time.Second)
				err := f.Error()
				if err != nil {
					connRegistry.Del(info.ID, f)
					return
				}
			}
		}(fc)
	}
}

// like RandId, but uses a crypto/rand for secure random identifiers
func SecureRandId(idlen int) (id string, err error) {
	b := make([]byte, idlen)
	n, err := rand.Read(b)

	if n != idlen {
		err = fmt.Errorf("Only generated %d random bytes, %d requested", n, idlen)
		return
	}

	if err != nil {
		return
	}

	id = fmt.Sprintf("%x", b)
	return
}

func RegistryIDs() map[string]string {
	return connRegistry.IDs()
}

func GetHTTPTransport(tunnel, ip, cc string) (*http.Transport, error) {
	// if transport := getTrasposportCache(tunnel, ip, cc); transport != nil {
	// 	return transport, nil
	// }
	var cert []tls.Certificate

	if cc != "" {
		bytes := []byte(cc)
		keypair, err := tls.X509KeyPair(bytes, bytes)
		if err != nil {
			return nil, err
		}
		cert = []tls.Certificate{keypair}
	}
	tlsCfg := &tls.Config{
		Certificates:       cert,
		InsecureSkipVerify: true,
		Renegotiation:      tls.RenegotiateOnceAsClient,
	}

	dialer, err := IPDialer(tunnel, ip)
	if err != nil {
		return nil, err
	}
	transport := &http.Transport{
		TLSClientConfig:       tlsCfg,
		Dial:                  dialer,
		ResponseHeaderTimeout: 120 * time.Second,
		MaxIdleConns:          100,
		IdleConnTimeout:       2 * time.Minute,
		TLSHandshakeTimeout:   60 * time.Second,
		DisableCompression:    false, // todo:
		// ExpectContinueTimeout: 1 * time.Second,
	}

	return transport, nil
}

func IPDialer(tunnel, ip string) (func(network, addr string) (net.Conn, error), error) {
	dialer, err := Dialer(tunnel)
	if err != nil {
		return nil, err
	}
	if ip == "" {
		return dialer, nil
	}
	return func(network, address string) (net.Conn, error) {
		port := strings.Split(address, ":")[1]
		address = fmt.Sprintf("%s:%s", ip, port)
		return dialer(network, address)
	}, nil
}

type ErrNoTunnel struct {
	ID string
}

func (err *ErrNoTunnel) Error() string {
	return fmt.Sprintf("无法连接隧道 %s", err.ID)
}

func Dialer(tunnel string) (func(network, addr string) (net.Conn, error), error) {
	if tunnel == "" {
		return net.Dial, nil
	}
	fc := connRegistry.Get(tunnel)
	if fc == nil {
		return nil, &ErrNoTunnel{tunnel}
	}
	return func(network, addr string) (net.Conn, error) {
		c, err := fc.Dial(network, addr)
		if err != nil {
			if fc.Error() != nil {
				connRegistry.Del(tunnel, fc)
			}
		}
		return c, err
	}, nil
}
func DialerConn(tunnel string) (net.Conn, error) {
	if tunnel == "" {
		return nil, errors.New("nil tunnel")
	}
	fc := connRegistry.Get(tunnel)
	if fc == nil {
		return nil, &ErrNoTunnel{tunnel}
	}
	return fc.GetConn()
}

const keyName = "tunnel"

func LoadTLSConfig(p string) (tlsConfig *tls.Config) {
	keyPath := func(kind string) string {
		name := fmt.Sprintf("%s.%s", keyName, kind)
		return path.Join(p, name)
	}
	var cert tls.Certificate
	cert, err := tls.LoadX509KeyPair(keyPath("crt"), keyPath("key"))
	if err != nil {
		return nil
	}

	return &tls.Config{
		Certificates: []tls.Certificate{cert},
	}

}

func LoadTLSConfigFromBytes(certPEM, keyPEM []byte) (tlsConfig *tls.Config) {
	cert, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		return nil
	}

	return &tls.Config{
		CipherSuites: []uint16{
			tls.TLS_RSA_WITH_AES_256_CBC_SHA,
			tls.TLS_RSA_WITH_AES_128_CBC_SHA,
			tls.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA,
			tls.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA,
			tls.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
			tls.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
			tls.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
			tls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
		},
		MinVersion:               tls.VersionTLS12,
		MaxVersion:               tls.VersionTLS12,
		PreferServerCipherSuites: true,
		Certificates:             []tls.Certificate{cert},
	}
}
