package main

import (
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"io"
	"io/ioutil"
	"os"
	"path"
	"time"

	"github.com/bugfan/i996-android/tunnel/conn"
)

func main() {
	certPEM, err := os.ReadFile("cert/cert.pem")
	if err != nil {
		fmt.Println("failed to read cert.pem:", err)
		return
	}
	tlsConfig := LoadTLSConfigFromBytes(certPEM)
	if tlsConfig != nil {
		tlsConfig.ClientSessionCache = tls.NewLRUClientSessionCache(5000)
	}
	tunnelAddr := "127.0.0.1:3333"
	for {
		c := NewClient(tunnelAddr, "testid", tlsConfig)
		fmt.Printf("server %s connectting\n", tunnelAddr)
		c.Run()
		time.Sleep(10 * time.Second)
	}
}

type Client struct {
	id         string
	tlsConfig  *tls.Config
	serverAddr string
	conn       *conn.FrameConn
}

func NewClient(serverAddr string, id string, tlsc *tls.Config) *Client {
	c := Client{
		id:         id,
		serverAddr: serverAddr,
		tlsConfig:  tlsc,
	}
	return &c
}

func (c *Client) GetID() string {
	if c.conn == nil {
		return c.id
	}
	info := c.conn.Info()
	if info == nil {
		return c.id
	}
	return info.ID
}

func (c *Client) Run() error {
	fc, err := conn.DialTLS("tcp", c.serverAddr, c.tlsConfig)
	if err != nil {
		return err
	}
	fmt.Printf("connected with %s\n", c.serverAddr)
	fc.SetInfo(&conn.Info{
		ID: c.id,
	})

	c.conn = fc

	for {
		fmt.Printf("accepting with %s\n", c.serverAddr)
		c, err := fc.Accept()
		if err == io.EOF {
			fmt.Printf("tunnel closed retrying \n")
			break
		}
		if err != nil {
			fmt.Printf("tunnel error retrying %s\n", err.Error())
			break
		}
		go func(c *conn.Conn) {
			c.Proxy()
		}(c)
	}
	return nil
}

const keyName = "tunnel"

func LoadTLSConfig(p string) *tls.Config {
	keyPath := func(kind string) string {
		name := fmt.Sprintf("%s.%s", keyName, kind)
		return path.Join(p, name)
	}
	rootPEM, err := ioutil.ReadFile(keyPath("crt"))
	if err != nil {
		fmt.Print("Load crt file fail\n")
		os.Exit(-1)
	}
	pool := x509.NewCertPool()
	ok := pool.AppendCertsFromPEM([]byte(rootPEM))
	if ok {
		return &tls.Config{
			RootCAs:            pool,
			InsecureSkipVerify: true,
		}
	}
	return nil
}

func LoadTLSConfigFromBytes(b []byte) *tls.Config {
	pool := x509.NewCertPool()
	ok := pool.AppendCertsFromPEM(b)
	if ok {
		return &tls.Config{
			RootCAs:            pool,
			InsecureSkipVerify: true,
		}
	}
	return nil
}
