package conn

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net"
	"sync"
	"time"
)

type Conn struct {
	id          uint64
	frame       *FrameConn
	buffer      *bytes.Buffer
	dialC       chan int
	writeLock   sync.Mutex
	writeDone   chan int
	connectDone chan int
	connectAddr string
	writeAble   chan int
	readC       chan []byte
	closed      bool
	err         error
	context     context.Context
	cancel      context.CancelFunc
}

func (c *Conn) logf(format string, args ...interface{}) {
	// c.frame.logf(fmt.Sprintf("[NO: %d]", c.id)+format+"\n", args...)
}

func (c *Conn) Write(p []byte) (int, error) {
	c.logf("to writ %s, len : %d", p, len(p))
	c.writeLock.Lock()
	defer c.writeLock.Unlock()
	select {
	case <-c.writeAble: // check writeable after lock
	case <-c.context.Done():
	}
	if c.closed {
		return 0, c.error()
	}
	nw, err := c.frame.writeDataTo(c.id, p)
	c.logf("writed %d, %#v", nw, err)
	return nw, err
}
func (c *Conn) Read(p []byte) (int, error) {
	nr, err := c.buffer.Read(p)
	if nr == 0 && err == io.EOF {
		newData, errr := c.read()
		if errr != nil {
			return 0, errr
		}
		c.buffer.Write(newData)
		nr, err = c.buffer.Read(p)
	}
	c.logf("readed %d, %#v", nr, err)
	return nr, err
}

func (c *Conn) read() ([]byte, error) {
	full := (cap(c.readC) - len(c.readC)) == 0
	newData, ok := <-c.readC
	if !ok {
		return nil, c.error()
	}
	if full {
		c.frame.writeDataWindow(c.id, (cap(c.readC) - len(c.readC)))
	}
	return newData, nil
}

// LocalAddr returns the local network address.
func (c *Conn) LocalAddr() net.Addr {
	return c.frame.net.LocalAddr()
}

// RemoteAddr returns the remote network address.
func (c *Conn) RemoteAddr() net.Addr {
	return c.frame.net.RemoteAddr()
}

// SetDeadline sets the read and write deadlines associated
// with the connection. It is equivalent to calling both
// SetReadDeadline and SetWriteDeadline.
//
// A deadline is an absolute time after which I/O operations
// fail with a timeout (see type Error) instead of
// blocking. The deadline applies to all future and pending
// I/O, not just the immediately following call to Read or
// Write. After a deadline has been exceeded, the connection
// can be refreshed by setting a deadline in the future.
//
// An idle timeout can be implemented by repeatedly extending
// the deadline after successful Read or Write calls.
//
// A zero value for t means I/O operations will not time out.
func (c *Conn) SetDeadline(t time.Time) error {
	return nil
}

// SetReadDeadline sets the deadline for future Read calls
// and any currently-blocked Read call.
// A zero value for t means Read will not time out.
func (c *Conn) SetReadDeadline(t time.Time) error {
	return nil
}

// SetWriteDeadline sets the deadline for future Write calls
// and any currently-blocked Write call.
// Even if write times out, it may return n > 0, indicating that
// some of the data was successfully written.
// A zero value for t means Write will not time out.
func (c *Conn) SetWriteDeadline(t time.Time) error {
	return nil
}

func (c *Conn) Connect(net, addr string) error {
	c.writeLock.Lock()
	defer c.writeLock.Unlock()
	if c.connectDone != nil {
		return errors.New("already connected")
	}
	c.frame.writeConnect(c.id, addr)
	return c.err
}

func (c *Conn) Proxy() error {
	c.writeLock.Lock()
	if c.connectDone == nil {
		c.connectDone = make(chan int)
	}
	c.writeLock.Unlock()
	<-c.connectDone
	// tconn, err := net.Dial("tcp", c.connectAddr)
	tconn, err := net.DialTimeout("tcp", c.connectAddr, time.Second*60)
	if err != nil {
		c.logf("proxy connect %s fail %s", c.connectAddr, err.Error())
		c.frame.writeConnectConfirm(c.id, err)
		c.Reset()
		return err
	}
	c.logf("proxy connect %s ok", c.connectAddr)
	c.frame.writeConnectConfirm(c.id, nil)
	c.logf("proxy joining")
	join(c, tconn)
	c.logf("proxy join done")
	return nil
}

func (c *Conn) error() error {
	if c.err != nil {
		return c.err
	}
	if c.closed {
		return io.EOF
	}
	return nil
}

func (c *Conn) closeConn() {
	defer func() {
		if recover() != nil {
		}
	}()
	if !c.closed {
		c.closed = true
		close(c.readC)
		c.cancel()
	}
}

func (c *Conn) reset() {
	defer func() {
		if recover() != nil {
		}
	}()
	if !c.closed {
		c.closed = true
		close(c.readC)
		c.err = errors.New("connection reset")
		c.cancel()
	}
}

func (c *Conn) Reset() {
	if !c.closed {
		c.reset()
		c.frame.resetConn(c.id)
		c.frame.cleanConn(c.id)
	}
}

func (c *Conn) Close() error {
	if !c.closed {
		c.closeConn()
		c.frame.closeConn(c.id)
		c.frame.cleanConn(c.id)
	}
	return nil
}

func (f *FrameConn) newConnWithID(id uint64) *Conn {
	c := make(chan int)
	close(c)
	ctx, cancel := context.WithCancel(f.context)
	return &Conn{
		id:        id,
		frame:     f,
		buffer:    bytes.NewBuffer(nil),
		dialC:     make(chan int),
		readC:     make(chan []byte, 128),
		writeDone: make(chan int),
		writeAble: c,
		context:   ctx,
		cancel:    cancel,
	}
}

func (f *FrameConn) newID() uint64 {
	f.lock.Lock()
	defer f.lock.Unlock()
	id := f.lastID + 2
	f.lastID = id
	return id
}

func (f *FrameConn) newConn() *Conn {
	id := f.newID()
	return f.newConnWithID(id)
}

func (f *FrameConn) accept(id uint64) (*Conn, error) {
	if ok := f.writeAccept(id); !ok {
		return nil, f.error()
	}
	conn := f.newConnWithID(id)
	f.setConn(conn)
	return conn, nil

}

func (f *FrameConn) Accept() (*Conn, error) {
	select {
	case <-f.context.Done():
		return nil, f.error()
	case id, ok := <-f.acceptC:
		if !ok {
			return nil, errors.New("closed")
		}
		return f.accept(id)
	}
}

func (f *FrameConn) Dial(net, addr string) (*Conn, error) {
	conn, err := f.DialTunnel()
	if err != nil {
		return nil, f.error()
	}
	err = conn.Connect(net, addr)
	if err != nil {
		conn.Close()
		return nil, err
	}
	return conn, nil
}

func (f *FrameConn) DialTunnel() (*Conn, error) {
	conn := f.newConn()
	f.setConn(conn)
	if !f.writeDial(conn.id) {
		f.cleanConn(conn.id)
		return nil, f.error()
	}
	select {
	case <-f.context.Done():
		f.cleanConn(conn.id)
		return nil, f.error()
	case <-conn.dialC:
		return conn, nil
	}
}
func (f *FrameConn) GetConn() (net.Conn, error) {
	if f.net != nil {
		return f.net, nil
	}
	return nil, errors.New("nil conn")
}
