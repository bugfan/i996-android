package libi996

import (
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"io"
	"time"

	"github.com/bugfan/i996-android/tunnel/conn"
)

// I996Client i996内网穿透客户端
type I996Client struct {
	id         string
	serverAddr string
	certPEM    []byte
	tlsConfig  *tls.Config
	conn       *conn.FrameConn
	running    bool
	stopChan   chan bool
}

var globalClient *I996Client

// NewClient 创建新的客户端实例
func NewClient() *I996Client {
	return &I996Client{
		running:  false,
		stopChan: make(chan bool),
	}
}

// SetConfig 设置配置
func (c *I996Client) SetConfig(serverAddr, token string, certPEM []byte) {
	c.serverAddr = serverAddr
	c.id = token
	c.certPEM = certPEM
	c.tlsConfig = loadTLSConfigFromBytes(certPEM)
	if c.tlsConfig != nil {
		c.tlsConfig.ClientSessionCache = tls.NewLRUClientSessionCache(5000)
	}
}

// Start 启动客户端连接
func (c *I996Client) Start() error {
	if c.running {
		return fmt.Errorf("client already running")
	}

	if c.serverAddr == "" || c.id == "" || c.tlsConfig == nil {
		return fmt.Errorf("config not set")
	}

	c.running = true
	globalClient = c
	go c.run()
	return nil
}

// Stop 停止客户端
func (c *I996Client) Stop() {
	if !c.running {
		return
	}

	c.running = false

	if c.conn != nil {
		c.conn.Close()
	}

	select {
	case c.stopChan <- true:
	default:
	}
}

// IsRunning 检查客户端是否正在运行
func (c *I996Client) IsRunning() bool {
	return c.running
}

// run 运行客户端主循环
func (c *I996Client) run() {
	for c.running {
		err := c.connect()
		if err != nil {
			// Silent retry
		}

		if !c.running {
			break
		}

		time.Sleep(5 * time.Second)
	}
}

// connect 连接到服务器
func (c *I996Client) connect() error {
	fc, err := conn.DialTLS("tcp", c.serverAddr, c.tlsConfig)
	if err != nil {
		return err
	}

	fc.SetInfo(&conn.Info{
		ID: c.id,
	})

	c.conn = fc

	defer func() {
		c.conn = nil
	}()

	for {
		select {
		case <-c.stopChan:
			return io.EOF
		default:
		}

		clientConn, err := fc.Accept()
		if err == io.EOF {
			break
		}

		if err != nil {
			break
		}

		go func(conn *conn.Conn) {
			conn.Proxy()
		}(clientConn)
	}

	return nil
}

// loadTLSConfigFromBytes 从字节加载TLS配置
func loadTLSConfigFromBytes(b []byte) *tls.Config {
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
