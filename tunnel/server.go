package tunnel

import (
	"crypto/tls"
	"errors"
	"fmt"
	"math/rand"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/bugfan/clotho/i996/engine/tcpmapping"
	"github.com/bugfan/clotho/i996/engine/tunnel/cert"
	"github.com/bugfan/clotho/i996/engine/tunnel/conn"
	"github.com/bugfan/clotho/services/dao"
)

// entry func
func Listen(addr string) error {
	tlsConf := LoadTLSConfigFromBytes([]byte(cert.Cert), []byte(cert.Key))
	fmt.Printf("Listening tcp://%s\n", addr)
	ListenTunnel(addr, tlsConf)
	return errors.New("tunnel server exited")
}

type ConnRegistry struct {
	conns   map[string]map[string]*conn.FrameConn
	rwMutex sync.RWMutex
}

func NewConnRegistry() *ConnRegistry {
	return &ConnRegistry{
		conns: make(map[string]map[string]*conn.FrameConn),
	}
}
func (r *ConnRegistry) Get(clientId string) *conn.FrameConn {
	r.rwMutex.RLock()
	defer r.rwMutex.RUnlock()
	if r.conns[clientId] == nil {
		return nil
	}

	length := len(r.conns[clientId])
	if length == 0 {
		return nil
	}
	idx := rand.Int() % length //nolint
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
	r.rwMutex.Lock()
	defer r.rwMutex.Unlock()
	if r.conns[clientId] == nil {
		r.conns[clientId] = make(map[string]*conn.FrameConn)
	}
	r.conns[clientId][ctl.RemoteAddr().String()] = ctl
	go func() {
		for {
			if ctl.Error() != nil || ctl.RemoteIP() == "" {
				err := r.Del(clientId, ctl)
				if err != nil {
					return
				}
				return
			}
			time.Sleep(1 * time.Second)
		}
	}()
	fmt.Printf("tunnels %#v\n", r.conns)
}

func (r *ConnRegistry) Del(clientId string, ctl *conn.FrameConn) error {
	r.rwMutex.Lock()
	defer r.rwMutex.Unlock()
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
	tcpmapping.Del(clientId)
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
			fmt.Printf("error accept connection %s\n", err.Error())
			continue
		}
		if fc == nil || fc.Info() == nil {
			continue
		}
		userId := fc.Info().ID
		if !dao.IsVip(userId) {
			fc.WriteX(conn.MessageNotVIP)
			fc.Close()
			fmt.Printf("not vip connect:%s\n", userId)
			continue
		}
		go func(userId string, fc *conn.FrameConn) {
			tcpmapping.Set(userId, fc)
		}(userId, fc)

		fmt.Printf("accepted tunnel %s\n", fc.Info())
		go func(f *conn.FrameConn) {
			info := f.Info()
			fmt.Printf("tunnel info %#v\n", info)
			if info == nil {
				fmt.Printf("cannot get info\n")
				f.Close()
				return
			}
			if info.ID == "" {
				id, err := SecureRandId(16)
				if err != nil {
					fmt.Printf("rand id gen failed")
				}
				info.ID = id

				fmt.Printf("no id found in client set id: %s\n", id)

				f.SetInfo(info)
			}
			fmt.Printf("add register id: %s\n", info.ID)
			connRegistry.Add(info.ID, f)
			for {
				time.Sleep(5e8)
				err := f.Error()
				if err != nil {
					err := connRegistry.Del(info.ID, f)
					if err != nil {
						fmt.Printf("error del register id: %s", err.Error())
						return
					}
					return
				}
			}
		}(fc)
	}
}

func RegistryIDs() map[string]string {
	return connRegistry.IDs()
}

func GetHTTPTransport(tunnel, ip string) (*http.Transport, error) {
	tlsCfg := &tls.Config{
		InsecureSkipVerify: true, //nolint
		Renegotiation:      tls.RenegotiateOnceAsClient,
	}

	dialer, err := GetDialFunc(tunnel, ip)
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
		DisableCompression:    true, // 用空间换计算
		// ExpectContinueTimeout: 1 * time.Second,
	}

	return transport, nil
}

func GetDialFunc(tunnel, ip string) (func(network, addr string) (net.Conn, error), error) {
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
				err := connRegistry.Del(tunnel, fc)
				if err != nil {
					return nil, err
				}
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

func Reset(tunnel string) {
	if tunnel == "" {
		return
	}
	fc := connRegistry.Get(tunnel)
	if fc == nil {
		return
	}
	go fc.WriteX(conn.MessageReload)
}

func LoadTLSConfigFromBytes(certPEM, keyPEM []byte) (tlsConfig *tls.Config) {
	cert, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		return nil
	}

	return &tls.Config{
		MinVersion:               tls.VersionTLS12,
		MaxVersion:               tls.VersionTLS13,
		PreferServerCipherSuites: true,
		Certificates:             []tls.Certificate{cert},
	}
}

// like RandId, but uses a crypto/rand for secure random identifiers
func SecureRandId(idlen int) (id string, err error) {
	b := make([]byte, idlen)
	n, err := rand.Read(b)
	if err != nil {
		return
	}

	if n != idlen {
		err = fmt.Errorf("only generated %d random bytes, %d requested", n, idlen)
		return
	}

	id = fmt.Sprintf("%x", b)
	return
}
