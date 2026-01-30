package libi996

import (
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"io"
	"time"

	"github.com/bugfan/i996-android/tunnel/conn"
)

// Logger 日志接口
type Logger interface {
	Log(message string)
}

// I996Client i996内网穿透客户端
type I996Client struct {
	id         string
	serverAddr string
	certPEM    []byte
	tlsConfig  *tls.Config
	conn       *conn.FrameConn
	running    bool
	stopChan   chan bool
	logger     Logger
}

var globalClient *I996Client

// NewClient 创建新的客户端实例
func NewClient() *I996Client {
	return &I996Client{
		running:  false,
		stopChan: make(chan bool),
	}
}

// SetLogger 设置日志回调
func (c *I996Client) SetLogger(logger Logger) {
	c.logger = logger
	// 同时设置 tunnel 包的全局 logger
	conn.SetGlobalLogger(func(msg string) {
		c.log(msg)
	})
	// 发送测试日志，确认 logger 设置成功
	c.log("【i996】Logger 设置成功！")
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
	c.log("正在连接到服务器 " + c.serverAddr)

	go c.run()
	return nil
}

// Stop 停止客户端
func (c *I996Client) Stop() {
	if !c.running {
		return
	}

	c.log("正在停止连接...")
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
			c.log(fmt.Sprintf("连接错误: %v", err))
		}

		if !c.running {
			break
		}

		c.log("连接断开，5秒后重连...")
		time.Sleep(5 * time.Second)
	}

	c.log("客户端已停止")
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
	c.log("连接成功！等待隧道请求...")

	defer func() {
		c.conn = nil
	}()

	for {
		select {
		case <-c.stopChan:
			c.log("收到停止信号")
			return io.EOF
		default:
		}

		clientConn, err := fc.Accept()
		if err == io.EOF {
			c.log("连接已断开-EOF")
			break
		}

		if err != nil {
			c.log(fmt.Sprintf("接受连接错误: %v", err))
			break
		}

		go func(conn *conn.Conn) {
			conn.Proxy()
		}(clientConn)
	}

	return nil
}

// log 内部日志函数
func (c *I996Client) log(msg string) {
	if c.logger != nil {
		c.logger.Log(msg)
	}
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
