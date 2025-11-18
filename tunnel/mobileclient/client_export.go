package mobileclient

import (
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"io"
	"sync"
	"time"

	"github.com/bugfan/i996-android/tunnel/conn"
)

// Client 是 Gomobile 导出的核心结构体
type Client struct {
	id         string
	serverAddr string
	tlsConfig  *tls.Config
	stopCh     chan struct{}
	wg         sync.WaitGroup
	statusCh   chan string
	frameConn  *conn.FrameConn
	mu         sync.Mutex
}

// NewClient 创建新的客户端实例
// serverAddr: 服务器地址，例如 "192.168.1.130:3333"
// clientID: 客户端ID，例如 "testid"
// certPEM: 证书内容（字符串形式）
func NewClient(serverAddr string, clientID string, certPEM string) (*Client, error) {
	if serverAddr == "" {
		return nil, fmt.Errorf("serverAddr cannot be empty")
	}
	if clientID == "" {
		return nil, fmt.Errorf("clientID cannot be empty")
	}
	if certPEM == "" {
		return nil, fmt.Errorf("certPEM cannot be empty")
	}

	// 加载 TLS 配置
	tlsConfig := loadTLSConfigFromBytes([]byte(certPEM))
	if tlsConfig == nil {
		return nil, fmt.Errorf("failed to load TLS config from certificate")
	}
	tlsConfig.ClientSessionCache = tls.NewLRUClientSessionCache(5000)

	c := &Client{
		id:         clientID,
		serverAddr: serverAddr,
		tlsConfig:  tlsConfig,
		stopCh:     make(chan struct{}),
		statusCh:   make(chan string, 100),
	}

	c.reportStatus(fmt.Sprintf("Client created for %s with ID %s", serverAddr, clientID))
	return c, nil
}

// Start 启动客户端（在后台 goroutine 中运行）
func (c *Client) Start() error {
	c.mu.Lock()
	defer c.mu.Unlock()

	// 检查是否已经启动
	select {
	case <-c.stopCh:
		return fmt.Errorf("client already stopped")
	default:
	}

	c.reportStatus("Starting tunnel client...")
	c.wg.Add(1)
	go c.runLoop()

	return nil
}

// Stop 停止客户端
func (c *Client) Stop() {
	c.mu.Lock()
	defer c.mu.Unlock()

	// 关闭 stopCh（如果还没关闭）
	select {
	case <-c.stopCh:
		// 已经关闭了
		return
	default:
		close(c.stopCh)
	}

	// 关闭当前连接
	if c.frameConn != nil {
		c.frameConn.Close()
	}

	c.reportStatus("Stopping tunnel client...")
	c.wg.Wait()
	c.reportStatus("Tunnel client stopped")

	// 关闭状态通道
	close(c.statusCh)
}

// GetStatus 获取下一个状态消息（阻塞调用）
// 返回空字符串表示通道已关闭
func (c *Client) GetStatus() string {
	msg, ok := <-c.statusCh
	if !ok {
		return ""
	}
	return msg
}

// IsRunning 检查客户端是否正在运行
func (c *Client) IsRunning() bool {
	select {
	case <-c.stopCh:
		return false
	default:
		return true
	}
}

// GetServerAddr 获取服务器地址
func (c *Client) GetServerAddr() string {
	return c.serverAddr
}

// GetClientID 获取客户端ID
func (c *Client) GetClientID() string {
	return c.id
}

// 内部方法：主循环
func (c *Client) runLoop() {
	defer c.wg.Done()

	for {
		// 检查是否需要停止
		select {
		case <-c.stopCh:
			c.reportStatus("Tunnel client stopped by user")
			return
		default:
		}

		// 尝试连接并运行
		err := c.runOnce()
		if err != nil {
			c.reportStatus(fmt.Sprintf("Connection error: %s. Retrying in 10s...", err.Error()))
		} else {
			c.reportStatus("Connection closed gracefully. Retrying in 10s...")
		}

		// 等待后重试
		select {
		case <-c.stopCh:
			c.reportStatus("Tunnel client stopped during retry wait")
			return
		case <-time.After(10 * time.Second):
			// 继续下一次重试
		}
	}
}

// 内部方法：单次连接尝试
func (c *Client) runOnce() error {
	// 建立 TLS 连接
	c.reportStatus(fmt.Sprintf("Connecting to %s...", c.serverAddr))
	fc, err := conn.DialTLS("tcp", c.serverAddr, c.tlsConfig)
	if err != nil {
		return fmt.Errorf("DialTLS error: %w", err)
	}

	c.mu.Lock()
	c.frameConn = fc
	c.mu.Unlock()

	c.reportStatus(fmt.Sprintf("Connected to %s successfully", c.serverAddr))

	// 发送客户端信息
	fc.SetInfo(&conn.Info{
		ID: c.id,
	})
	c.reportStatus(fmt.Sprintf("Registered with ID: %s", c.id))

	// 主循环：接受服务器请求
	for {
		// 检查是否需要停止
		select {
		case <-c.stopCh:
			fc.Close()
			return nil
		default:
		}

		c.reportStatus("Waiting for server requests...")
		serverConn, err := fc.Accept()

		if err == io.EOF {
			c.reportStatus("Server closed connection")
			return nil
		}

		if err != nil {
			return fmt.Errorf("Accept error: %w", err)
		}

		c.reportStatus("Received proxy request from server")

		// 启动 goroutine 处理代理
		go func(conn *conn.Conn) {
			defer func() {
				if r := recover(); r != nil {
					c.reportStatus(fmt.Sprintf("Proxy panic: %v", r))
				}
			}()

			err := conn.Proxy()
			if err != nil {
				c.reportStatus(fmt.Sprintf("Proxy error: %s", err.Error()))
			} else {
				c.reportStatus("Proxy session completed")
			}
		}(serverConn)
	}
}

// 内部方法：报告状态
func (c *Client) reportStatus(msg string) {
	timestamp := time.Now().Format("15:04:05")
	fullMsg := fmt.Sprintf("[%s] %s", timestamp, msg)

	// 非阻塞发送
	select {
	case c.statusCh <- fullMsg:
	default:
		// 通道满了，丢弃旧消息
	}

	// 同时打印到 Go 侧日志
	fmt.Println(fullMsg)
}

// loadTLSConfigFromBytes 从 PEM 字节加载 TLS 配置
func loadTLSConfigFromBytes(certPEM []byte) *tls.Config {
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(certPEM) {
		fmt.Println("Failed to append certs from PEM")
		return nil
	}

	return &tls.Config{
		RootCAs:            pool,
		InsecureSkipVerify: true, // 客户端通常需要跳过自签名证书验证
	}
}
