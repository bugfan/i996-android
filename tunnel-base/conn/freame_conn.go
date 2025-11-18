package conn

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"time"
)

type Info struct {
	ID string
}

type FrameConn struct {
	info     *Info
	infoC    chan int
	net      net.Conn
	lastID   uint64
	conns    map[uint64]*Conn
	lock     sync.RWMutex
	context  context.Context
	cancel   context.CancelFunc
	acceptC  chan uint64
	writeC   chan data
	err      error
	logging  bool
	name     string
	lastPing time.Time
	lastPong time.Time
	close    chan int
	vg       *sync.WaitGroup
}

const (
	controlID       uint64 = 0
	userConnIDStart uint64 = 128
)

var globalFrameConnID = 0

func newFrameConn(conn net.Conn, dialer bool) (*FrameConn, error) {
	ctx, cancel := context.WithCancel(context.Background())
	globalFrameConnID++
	start := userConnIDStart
	name := "client"
	if !dialer {
		start++
		name = "server"
	}
	name = fmt.Sprintf("%s:%d", name, globalFrameConnID)

	f := &FrameConn{
		net:      conn,
		lastID:   start,
		cancel:   cancel,
		context:  ctx,
		writeC:   make(chan data),
		conns:    make(map[uint64]*Conn),
		acceptC:  make(chan uint64, 128),
		lastPing: time.Now(),
		lastPong: time.Now(),
		name:     name,
		logging:  false,
		vg:       &sync.WaitGroup{},
	}

	f.logging = true
	f.logf("starting reader and writer %s", name)
	f.vg.Add(2)
	go f.runReader()
	go f.runWriter()
	go f.supervisor()
	return f, nil
}

func Dial(network, addr string) (*FrameConn, error) {
	fmt.Printf("dialing: %s, %s\n", addr, network)
	conn, err := net.Dial(network, addr)
	if err != nil {
		return nil, err
	}
	f, err := newFrameConn(conn, true)
	if err != nil {
		fmt.Printf("newFrameConn fail %s\n", err.Error())
	} else {
		fmt.Printf("dialed fc lastid : %d\n", f.lastID)
	}
	return f, err
}

func DialTLS(network, addr string, tlsCfg *tls.Config) (*FrameConn, error) {
	fmt.Printf("dialing: %s, %s\n", addr, network)
	conn, err := net.Dial(network, addr)
	if err != nil {
		return nil, err
	}
	conn = tls.Client(conn, tlsCfg)
	f, err := newFrameConn(conn, true)
	if err != nil {
		fmt.Printf("newFrameConn fail %s\n", err.Error())
	} else {
		fmt.Printf("dialed fc lastid : %d\n", f.lastID)
	}
	return f, err
}

type FrameListener struct {
	listener net.Listener
	tlsCfg   *tls.Config
}

func Listen(addr, typ string, tlsCfg *tls.Config) (*FrameListener, error) {
	fmt.Printf("listen: %s, %s\n", addr, typ)
	listener, err := net.Listen(typ, addr)
	if err != nil {
		return nil, err
	}

	f := &FrameListener{
		listener: listener,
		tlsCfg:   tlsCfg,
	}
	return f, nil
}

func (l *FrameListener) Accept() (*FrameConn, error) {
	fmt.Printf("accepting\n")
	conn, err := l.listener.Accept()
	if err != nil {
		return nil, err
	}
	if l.tlsCfg != nil {
		conn = tls.Server(conn, l.tlsCfg)
	}

	fc, err := newFrameConn(conn, false)
	if err != nil {
		fmt.Printf("accept fail %s", err.Error())
	}
	fmt.Printf("accepted fc last id %d\n", fc.lastID)
	return fc, err
}

func (c *FrameConn) closeConn(id uint64) error {
	c.writeClose(id)
	return nil
}
func (c *FrameConn) resetConn(id uint64) error {
	c.writeReset(id)
	return nil
}

func (c *FrameConn) Reset() {
	c.err = io.EOF
	c.logf("reseting frameconn\n")
	c.clean()
	c.logf("reset closing reader and writer\n")
	c.cancel()
	c.net.Close()
	c.vg.Wait()
	c.logf("reset net conn\n")
}

func (c *FrameConn) Close() error {
	c.err = io.EOF
	c.logf("closing frameconn\n")
	c.writeTunnelClose()
	c.clean()
	c.close = make(chan int)
	<-c.close
	c.logf("closing reader and writer\n")
	c.cancel()
	c.vg.Wait()
	c.logf("closing net conn\n")

	return c.net.Close()
}

func (c *FrameConn) Info() *Info {
	c.lock.Lock()
	if c.infoC == nil {
		c.infoC = make(chan int)
	}
	c.lock.Unlock()
	select {
	case <-c.infoC:
		return c.info
	case <-c.context.Done():
		return nil
	}
}

func (c *FrameConn) setInfo(i *Info) {
	c.lock.Lock()
	if c.infoC == nil {
		c.infoC = make(chan int)
		close(c.infoC)
	} else {
		select {
		case <-c.infoC:
		default:
			close(c.infoC)
		}
	}
	c.info = i
	c.lock.Unlock()
}
func (c *FrameConn) SetInfo(i *Info) {
	c.setInfo(i)
	c.writeInfo(i)
}
func (c *FrameConn) RemoteAddr() net.Addr {
	return c.net.RemoteAddr()
}

func (c *FrameConn) RemoteIP() string {
	return strings.SplitN(c.net.RemoteAddr().String(), ":", 2)[0]
}

func (c *FrameConn) clean() {
	c.logf("cleaning frameconn start\n")
	c.err = io.EOF
	c.lock.Lock()
	defer c.lock.Unlock()
	for id, conn := range c.conns {
		c.logf("closing conn %d", id)
		conn.reset()
		delete(c.conns, id)
	}
	c.logf("cleaning frameconn clean end\n")
}

func (c *FrameConn) occurError(err error) {
	c.logf("error occured %s\n", err.Error())
	c.err = err
	c.lock.Lock()
	defer c.lock.Unlock()
	c.cancel()
	c.net.Close()
}

func (c *FrameConn) getConn(id uint64) (*Conn, bool) {
	c.lock.RLock()
	conn, ok := c.conns[id]
	c.lock.RUnlock()
	return conn, ok
}

func (f *FrameConn) cleanConn(id uint64) {
	f.lock.Lock()
	delete(f.conns, id)
	f.lock.Unlock()
}
func (f *FrameConn) setConn(conn *Conn) {
	f.lock.Lock()
	f.conns[conn.id] = conn
	f.lock.Unlock()
}

func (c *FrameConn) logf(format string, args ...interface{}) {
	if c.logging {
		// fmt.Printf("["+c.name+"]"+format+"\n", args...)
	}
}

func (f *FrameConn) error() error {
	if f.err != nil {
		return f.err
	}
	switch f.context.Err() {
	case context.Canceled:
		return io.EOF
	case context.DeadlineExceeded:
		return io.EOF
	default:
		return nil
	}
}

func (f *FrameConn) Error() error {
	return f.error()
}

func (c *FrameConn) supervisor() {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	for err := c.context.Err(); err == nil; err = c.context.Err() {
		select {
		case <-ticker.C:
			now := time.Now()
			if now.Sub(c.lastPing) > 15*time.Second {
				c.occurError(errors.New("ping timeout"))
				return
			}
			if now.Sub(c.lastPong) > 15*time.Second {
				c.occurError(errors.New("pong timeout"))
				return
			}

		case <-c.context.Done():
			return
		}
	}
}
