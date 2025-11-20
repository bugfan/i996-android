package mobile

import (
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/bugfan/clotho/i996/engine/tunnel/cert"
	"github.com/bugfan/clotho/i996/engine/tunnel/client"
	"github.com/bugfan/clotho/i996/engine/tunnel/conn"
	"github.com/sirupsen/logrus"
)

const remoteAddr = "i996.me:8223"

// LogCallback 日志回调接口
type LogCallback interface {
	OnLog(message string)
}

// customWriter 自定义 Writer，用于捕获标准输出
type customWriter struct {
	callback LogCallback
}

func (w *customWriter) Write(p []byte) (n int, err error) {
	if w.callback != nil {
		w.callback.OnLog(string(p))
	}
	return len(p), nil
}

// I996Client 是暴露给 Android 的客户端接口
type I996Client struct {
	token          string
	client         *client.Client
	running        bool
	mu             sync.Mutex
	ctx            context.Context
	cancel         context.CancelFunc
	tlsConfig      *tls.Config
	logCallback    LogCallback
	frameConn      *conn.FrameConn
	originalStdout *os.File
	originalStderr *os.File
}

// customHook 自定义 logrus hook，用于捕获日志
type customHook struct {
	callback LogCallback
}

func (h *customHook) Levels() []logrus.Level {
	return logrus.AllLevels
}

func (h *customHook) Fire(entry *logrus.Entry) error {
	if h.callback != nil {
		msg := entry.Message
		if len(entry.Data) > 0 {
			msg = fmt.Sprintf("%s %v", msg, entry.Data)
		}
		h.callback.OnLog(fmt.Sprintf("[%s] %s", entry.Level.String(), msg))
	}
	return nil
}

// NewI996Client 创建一个新的客户端实例
func NewI996Client(token string) *I996Client {
	logrus.SetLevel(logrus.InfoLevel)

	tlsConfig := client.LoadTLSConfigFromBytes([]byte(cert.Cert))
	if tlsConfig != nil {
		tlsConfig.ClientSessionCache = tls.NewLRUClientSessionCache(5000)
	}

	ctx, cancel := context.WithCancel(context.Background())

	return &I996Client{
		token:     token,
		tlsConfig: tlsConfig,
		ctx:       ctx,
		cancel:    cancel,
	}
}

// SetLogCallback 设置日志回调
func (c *I996Client) SetLogCallback(callback LogCallback) {
	c.mu.Lock()
	defer c.mu.Unlock()

	c.logCallback = callback

	// 移除旧的 hooks
	logrus.StandardLogger().ReplaceHooks(make(logrus.LevelHooks))

	// 添加自定义 hook 到 logrus
	if callback != nil {
		logrus.AddHook(&customHook{callback: callback})

		// 重定向标准输出到我们的 writer
		// 注意：这会影响所有使用 fmt.Print 的输出
		writer := &customWriter{callback: callback}
		logrus.SetOutput(writer)

		// 同时也可以考虑捕获 fmt.Print 的输出
		// 但这在 Android 中可能不太可靠，因为输出通常会到 logcat
	}
}

// Start 启动客户端连接
func (c *I996Client) Start() error {
	c.mu.Lock()
	if c.running {
		c.mu.Unlock()
		return fmt.Errorf("client is already running")
	}
	c.running = true

	// 重新创建 context
	c.ctx, c.cancel = context.WithCancel(context.Background())
	c.mu.Unlock()

	go c.run()
	return nil
}

// Stop 停止客户端连接
func (c *I996Client) Stop() {
	c.mu.Lock()
	if !c.running {
		c.mu.Unlock()
		return
	}
	c.running = false

	// 1. 取消 context
	if c.cancel != nil {
		c.cancel()
	}

	// 2. 关闭 FrameConn（这是关键！）
	if c.frameConn != nil {
		c.frameConn.Close()
		c.frameConn = nil
	}

	c.mu.Unlock()

	// 发送日志
	if c.logCallback != nil {
		c.logCallback.OnLog("[INFO] 客户端已停止")
	}

	logrus.Info("客户端停止完成")
}

// IsRunning 检查客户端是否正在运行
func (c *I996Client) IsRunning() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.running
}

// run 内部运行循环
func (c *I996Client) run() {
	defer func() {
		c.mu.Lock()
		c.running = false
		if c.frameConn != nil {
			c.frameConn.Close()
			c.frameConn = nil
		}
		c.mu.Unlock()

		if c.logCallback != nil {
			c.logCallback.OnLog("[INFO] 客户端运行循环已退出")
		}
	}()

	for {
		// 检查是否被停止
		select {
		case <-c.ctx.Done():
			if c.logCallback != nil {
				c.logCallback.OnLog("[INFO] 收到停止信号，正在退出...")
			}
			return
		default:
		}

		// 检查 running 状态
		c.mu.Lock()
		shouldRun := c.running
		c.mu.Unlock()

		if !shouldRun {
			return
		}

		// 尝试连接
		if c.logCallback != nil {
			c.logCallback.OnLog(fmt.Sprintf("[INFO] 正在连接 %s...", remoteAddr))
		}

		fc, err := conn.DialTLS("tcp", remoteAddr, c.tlsConfig)
		if err != nil {
			if c.logCallback != nil {
				c.logCallback.OnLog(fmt.Sprintf("[ERROR] 连接失败: %v", err))
			}

			// 等待后重试
			select {
			case <-c.ctx.Done():
				return
			case <-time.After(5 * time.Second):
				if c.logCallback != nil {
					c.logCallback.OnLog("[INFO] 5秒后重新连接...")
				}
				continue
			}
		}

		logrus.Infof("连接成功!\n")

		// 保存 FrameConn
		c.mu.Lock()
		c.frameConn = fc
		c.mu.Unlock()

		// 设置客户端信息
		fc.SetInfo(&conn.Info{
			ID: c.token,
		})

		// 获取并显示连接信息
		// 服务器可能会在连接建立后发送欢迎信息
		// 这些信息通常会被打印到标准输出
		// 我们需要给一点时间让这些信息被接收和处理
		time.Sleep(500 * time.Millisecond)

		// 尝试获取连接信息
		info := fc.Info()
		if info != nil && c.logCallback != nil {
			c.logCallback.OnLog(fmt.Sprintf("[INFO] 连接ID: %s", info.ID))
		}

		// Accept 循环
		c.acceptLoop(fc)

		// Accept 循环结束，清理连接
		c.mu.Lock()
		if c.frameConn == fc {
			c.frameConn = nil
		}
		c.mu.Unlock()

		fc.Close()

		// 检查是否应该重连
		select {
		case <-c.ctx.Done():
			return
		default:
			// 检查 running 状态
			c.mu.Lock()
			shouldRun := c.running
			c.mu.Unlock()

			if !shouldRun {
				return
			}

			// 等待后重连
			select {
			case <-c.ctx.Done():
				return
			case <-time.After(5 * time.Second):
				if c.logCallback != nil {
					c.logCallback.OnLog("[INFO] 5秒后重新连接...")
				}
			}
		}
	}
}

// acceptLoop 处理连接的 Accept 循环
func (c *I996Client) acceptLoop(fc *conn.FrameConn) {
	for {
		// 检查是否被停止
		select {
		case <-c.ctx.Done():
			return
		default:
		}

		// Accept 新连接（这是阻塞调用）
		// 当 fc.Close() 被调用时，这里会返回错误
		newConn, err := fc.Accept()

		if err == io.EOF {
			logrus.Info("链接中断了-EOF")
			if c.logCallback != nil {
				c.logCallback.OnLog("[INFO] 链接中断了-EOF")
			}
			return
		}

		// 检查是否是 NotVIP 错误
		// NotVIP 应该是一个错误类型，不是 conn 的字段
		if err != nil {
			errStr := err.Error()
			// 通过字符串匹配来判断是否是 VIP 错误
			if strings.Contains(strings.ToLower(errStr), "not vip") ||
				strings.Contains(strings.ToLower(errStr), "notvip") ||
				strings.Contains(errStr, "会员") {
				msg := `抱歉,您目前还不是会员,无法使用此方式连接,请访问"https://www.i996.me"网址,打赏后方可成为会员!`
				if c.logCallback != nil {
					c.logCallback.OnLog("[ERROR] " + msg)
				}
				logrus.Info(msg)
				return
			}

			// 检查是否是因为连接被主动关闭
			select {
			case <-c.ctx.Done():
				// 是主动停止，不记录错误
				return
			default:
				// 其他错误
				errMsg := fmt.Sprintf("retrying %s\n", err.Error())
				logrus.Error(errMsg)
				if c.logCallback != nil {
					c.logCallback.OnLog("[ERROR] " + errMsg)
				}
				return
			}
		}

		// 在新的 goroutine 中处理代理
		go func(proxyCon *conn.Conn) {
			defer func() {
				if r := recover(); r != nil {
					panicMsg := fmt.Sprintf("proxy panic: %v", r)
					logrus.Error(panicMsg)
					if c.logCallback != nil {
						c.logCallback.OnLog("[ERROR] " + panicMsg)
					}
				}
			}()

			if err := proxyCon.Proxy(); err != nil {
				// 只在没有停止的情况下记录错误
				select {
				case <-c.ctx.Done():
					// 已停止，忽略错误
				default:
					errMsg := fmt.Sprintf("proxy error : %s", err.Error())
					fmt.Println(errMsg) // 保持原始输出
					if c.logCallback != nil {
						c.logCallback.OnLog("[ERROR] " + errMsg)
					}
				}
			}
		}(newConn)
	}
}

// GetToken 获取当前使用的 token
func (c *I996Client) GetToken() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.token
}

// SetToken 设置新的 token（需要重启客户端才能生效）
func (c *I996Client) SetToken(token string) {
	c.mu.Lock()
	c.token = token
	c.mu.Unlock()
}

// ForceStop 强制停止（用于确保完全停止）
func (c *I996Client) ForceStop() {
	if c.logCallback != nil {
		c.logCallback.OnLog("[INFO] 正在强制停止...")
	}

	c.mu.Lock()
	c.running = false

	// 取消 context
	if c.cancel != nil {
		c.cancel()
	}

	// 关闭 FrameConn
	if c.frameConn != nil {
		c.frameConn.Close()
		c.frameConn = nil
	}
	c.mu.Unlock()

	// 等待一小段时间确保关闭完成
	time.Sleep(200 * time.Millisecond)

	if c.logCallback != nil {
		c.logCallback.OnLog("[INFO] 强制停止完成")
	}

	logrus.Info("强制停止完成")
}
