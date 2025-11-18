package mobileclient

import (
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"io"
	"sync"
	"time"

	// 确保这里的导入路径与您的 go.mod 保持一致
	"github.com/bugfan/i996-android/tunnel/conn"
)

// Client 是 Gomobile 导出的核心结构体
type Client struct {
	id         string
	serverAddr string
	tlsConfig  *tls.Config
	stopCh     chan struct{}
	wg         sync.WaitGroup
	// 状态报告（可选，用于安卓端显示）
	statusCh chan string
}

// NewClient 是导出的构造函数，用于创建 Client 实例
// certPEM 必须是您的 cert.pem 文件的内容
func NewClient(serverAddr string, clientID string, certPEM string) *Client {
	// 1. 加载 TLS 配置
	tlsConfig := LoadTLSConfigFromBytes([]byte(certPEM))
	if tlsConfig != nil {
		tlsConfig.ClientSessionCache = tls.NewLRUClientSessionCache(5000)
	}

	// 2. 创建 Client 实例
	c := &Client{
		id:         clientID,
		serverAddr: serverAddr,
		tlsConfig:  tlsConfig,
		stopCh:     make(chan struct{}),
		statusCh:   make(chan string, 10), // 缓冲通道用于状态报告
	}
	return c
}

// Start 在后台 goroutine 中运行客户端连接循环
func (c *Client) Start() {
	c.reportStatus("Starting tunnel client...")
	c.wg.Add(1)
	go func() {
		defer c.wg.Done()
		for {
			err := c.runOnce()
			if err != nil {
				c.reportStatus(fmt.Sprintf("Tunnel client loop error: %s. Retrying in 10s...", err.Error()))
			} else {
				c.reportStatus("Tunnel loop finished gracefully. Retrying in 10s...")
			}

			select {
			case <-c.stopCh:
				c.reportStatus("Tunnel client stopped by user command.")
				return
			case <-time.After(10 * time.Second):
				// 继续下一次重试
			}
		}
	}()
}

// Stop 停止客户端循环并等待所有 goroutine 退出
func (c *Client) Stop() {
	close(c.stopCh)
	c.wg.Wait()
	c.reportStatus("Tunnel client fully shut down.")
}

// GetStatusChannel 返回一个只读通道，用于 Android 接收 Go 运行时的状态消息
func (c *Client) GetStatusChannel() <-chan string {
	return c.statusCh
}

func (c *Client) reportStatus(msg string) {
	// 非阻塞发送状态，如果通道满了则丢弃旧消息
	select {
	case c.statusCh <- msg:
	default:
	}
	fmt.Println(msg) // 同时也打印到 Go 侧标准输出
}

// runOnce 负责一次连接尝试和隧道主循环
func (c *Client) runOnce() error {
	// 使用 conn.go 提供的 DialTLS
	fc, err := conn.DialTLS("tcp", c.serverAddr, c.tlsConfig)
	if err != nil {
		return fmt.Errorf("DialTLS error: %w", err)
	}
	c.reportStatus(fmt.Sprintf("Connected with %s", c.serverAddr))

	// 发送 Info 包进行注册
	fc.SetInfo(&conn.Info{
		ID: c.id,
	})

	// 接受服务器请求的主循环 (对应 client.go 的 Run() 内部循环)
	for {
		c.reportStatus(fmt.Sprintf("Accepting connections from server %s", c.serverAddr))

		select {
		case <-c.stopCh:
			fc.Close() // 收到停止信号，关闭连接以解锁 Accept
			return nil
		default:
			// continue
		}

		serverConn, err := fc.Accept()
		if err == io.EOF {
			c.reportStatus("Tunnel closed gracefully.")
			break // 跳出 runOnce 内部循环，进入外层重试
		}
		if err != nil {
			return fmt.Errorf("Tunnel Accept error: %w", err)
		}

		// 启动 goroutine 处理代理转发 (Proxy)
		go func(c *conn.Conn) {
			c.Proxy()
			// c.reportStatus(fmt.Sprintf("Proxy session done for connection ID %d", c.ID()))
		}(serverConn)
	}
	return nil
}

// LoadTLSConfigFromBytes 是从 client.go 提取的辅助函数
func LoadTLSConfigFromBytes(certPEM []byte) *tls.Config {
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(certPEM) {
		fmt.Println("failed to append certs")
		return nil
	}

	return &tls.Config{
		RootCAs:            pool,
		InsecureSkipVerify: true, // 客户端通常需要跳过自签名证书验证
	}
}
