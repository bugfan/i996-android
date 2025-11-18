package server

import (
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/bugfan/i996-android/tunnel/conn"
	"github.com/bugfan/i996-android/tunnel/utils"
)

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
			info := f.Info()
			fmt.Printf("tunnel info %#v\n", info)
			if info == nil {
				fmt.Printf("cannot get info\n")
				f.Close()
				return
			}
			if info.ID == "" {
				id, _ := utils.SecureRandId(16)
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
