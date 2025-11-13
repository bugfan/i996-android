package client

import (
	"crypto/tls"
	"fmt"
	"io"

	"github.com/bugfan/i996-android/tunnel/conn"
)

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
