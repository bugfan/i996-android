package main

import (
	"crypto/tls"
	"crypto/x509"
	"flag"
	"fmt"
	"io"
	"os"
	"time"

	"github.com/bugfan/clotho/i996/engine/tunnel/cert"
	"github.com/bugfan/clotho/i996/engine/tunnel/conn"
)

var (
	remoteAddr string = "i996.me:8223"
	token      *string
)

func init() {
	token = flag.String("token", "", "Token")
	flag.Parse()
	if *token == "" {
		fmt.Printf(`【注意】请指定Token，例如'i996.exe -token xxxxx'或'i996.exe -token=xxxxx'
`)
		os.Exit(0)
	}
}

func main() {
	tlsConfig := LoadTLSConfigFromBytes([]byte(cert.Cert))
	if tlsConfig != nil {
		tlsConfig.ClientSessionCache = tls.NewLRUClientSessionCache(5000)
	}
	for {
		c := NewClient(remoteAddr, *token, tlsConfig)
		err := c.Run()
		if err != nil {
			fmt.Println("error: ", err)
		}
		time.Sleep(5 * time.Second)
	}
}

func LoadTLSConfigFromBytes(b []byte) *tls.Config {
	pool := x509.NewCertPool()
	ok := pool.AppendCertsFromPEM(b)
	if ok {
		return &tls.Config{
			RootCAs:            pool,
			InsecureSkipVerify: true, //nolint
		}
	}
	return nil
}

type Client struct {
	id         string
	tlsConfig  *tls.Config
	serverAddr string
	conn       *conn.FrameConn
}

func NewClient(serverAddr, id string, tlsc *tls.Config) *Client {
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

	fc.SetInfo(&conn.Info{
		ID: c.id,
	})

	c.conn = fc

	for {
		c, err := fc.Accept()
		if err == io.EOF {
			fmt.Println("【i996】链接中断了-EOF")
			break
		}

		if err == conn.NotVIP {
			fmt.Println(`【i996】抱歉,您目前还不是会员,无法使用此方式连接,请访问"https://www.i996.me"网址,打赏后方可成为会员!`)
			os.Exit(0)
		}

		if err != nil {
			fmt.Printf("【i996】重试 %s\n", err.Error())
			break
		}

		go func(c *conn.Conn) {
			if err := c.Proxy(); err != nil {
				fmt.Printf("【i996】代理错误 %s\n", err.Error())
			}
		}(c)
	}
	return nil
}
