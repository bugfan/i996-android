package conn

import (
	"bufio"
	"bytes"
	"context"
	"crypto/tls"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"time"
)

// protocol

const (
	commandData           uint64 = 0
	commandDataConfirm    uint64 = 1
	commandDataWindow     uint64 = 2
	commandConnect        uint64 = 3
	commandConnectConfirm uint64 = 4
	commandDial           uint64 = 128 + iota
	commandAccept
	commandClose
	commandReset
	commandPing
	commandPong
	commandTunnelClose
	commandTunnelCloseConfirm
	commandInfo
)

type data interface {
	tp() uint64
}

type dialData struct {
	id uint64
}

func (d *dialData) tp() uint64 {
	return commandDial
}

func newDialData(id uint64) data {
	return &dialData{
		id: id,
	}
}

type acceptData struct {
	id uint64
}

func (d *acceptData) tp() uint64 {
	return commandAccept
}

func newAcceptData(id uint64) data {
	return &acceptData{
		id: id,
	}
}

type closeData struct {
	id uint64
}

func (d *closeData) tp() uint64 {
	return commandClose
}

func newCloseData(id uint64) data {
	return &closeData{
		id: id,
	}
}

type resetData struct {
	id uint64
}

func (d *resetData) tp() uint64 {
	return commandReset
}

func newResetData(id uint64) data {
	return &resetData{
		id: id,
	}
}

type pingData struct {
}

func (d *pingData) tp() uint64 {
	return commandPing
}

func newPingData() data {
	return &pingData{}
}

type pongData struct {
}

func (d *pongData) tp() uint64 {
	return commandPong
}

func newPongData() data {
	return &pongData{}
}

type dataData struct {
	id   uint64
	data []byte
	nw   int
	err  error
}

func (d *dataData) tp() uint64 {
	return commandData
}

func newDataData(id uint64, d []byte) *dataData {
	return &dataData{
		id:   id,
		data: d,
	}
}

type connectData struct {
	id   uint64
	addr []byte
	err  error
}

func (d *connectData) tp() uint64 {
	return commandConnect
}

func newConnectData(id uint64, addr string) *connectData {
	return &connectData{
		id:   id,
		addr: []byte(addr),
	}
}

type connectConfirmData struct {
	id  uint64
	err []byte
}

func (d *connectConfirmData) tp() uint64 {
	return commandConnectConfirm
}
func (d *connectConfirmData) error() error {
	return errors.New(string(d.err))
}

func newConnectConfirmData(id uint64, err error) *connectConfirmData {
	var buf []byte = nil
	if err != nil {
		buf = []byte(err.Error())
	}
	return &connectConfirmData{
		id:  id,
		err: buf,
	}
}

type dataConfirmData struct {
	id   uint64
	size int // window size
}

func (d *dataConfirmData) tp() uint64 {
	return commandDataConfirm
}

func newDataConfirmData(id uint64, size int) data {
	return &dataConfirmData{id, size}
}

type dataWindowData struct {
	id   uint64
	size int // window size
}

func (d *dataWindowData) tp() uint64 {
	return commandDataWindow
}

func newDataWindowData(id uint64, size int) data {
	return &dataWindowData{id, size}
}

type tunnelCloseData struct {
}

func (d *tunnelCloseData) tp() uint64 {
	return commandTunnelClose
}

func newTunnelCloseData() data {
	return &tunnelCloseData{}
}

type tunnelCloseConfirmData struct {
	sent chan int
}

func (d *tunnelCloseConfirmData) tp() uint64 {
	return commandTunnelCloseConfirm
}

func newTunnelCloseConfirmData() data {
	return &tunnelCloseConfirmData{
		sent: make(chan int),
	}
}

type infoData struct {
	size uint64
	info []byte
}

func (d *infoData) tp() uint64 {
	return commandInfo
}

func newInfoData(i *Info) data {
	d, _ := json.Marshal(i)
	return &infoData{
		size: uint64(len(d)),
		info: d,
	}
}

// Info
type Info struct {
	ID string
}

// Conn
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

// FrameConn
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

// read

func (c *FrameConn) runReader() {
	c.logf("reader starting")
	reader := bufio.NewReader(c.net)
	err := c.context.Err()
	for ; err == nil; err = c.context.Err() {
		c.net.SetReadDeadline(time.Now().Add(30 * time.Second))
		id, err := c.readUint64WithError(reader)
		if err != nil {
			c.occurError(err)
			break
		}
		c.net.SetReadDeadline(time.Time{})
		c.logf("read id %d ", id)
		switch {
		case id == controlID:
			c.logf("control")
			c.controlProcess(reader)
		case id >= userConnIDStart:
			c.logf("data")
			c.dataProcess(id, reader)
		default:
			c.logf("unexpected")
			c.occurError(fmt.Errorf("unexpected connection id %d", id))
		}
	}
	c.logf("reader stoping")
	c.vg.Done()
	c.logf("reader stoped")
}

func (c *FrameConn) dataProcess(id uint64, reader *bufio.Reader) {
	sz, ok := c.readUint64(reader)
	c.logf("data: to readsize %d\n", sz)
	if !ok {
		return
	}
	buf := make([]byte, sz)
	readed := 0
	for {
		nr, err := reader.Read(buf[readed:])
		c.logf("data: readed %d + %d  = %d", readed, nr, readed+nr)
		if err == io.EOF {
			c.Close()
			return
		}
		if err != nil {
			c.occurError(err)
			return
		}
		readed += nr
		if uint64(readed) >= sz {
			break
		}
	}
	conn, ok := c.getConn(id)
	if !ok {
		c.logf("unexpected id %d, received size: %d\n", id, readed)
		return
	}
	defer func() {
		if r := recover(); r != nil {
			if conn.closed {
				return
			}
		}
	}()
	select {
	case <-c.context.Done():
		return
	case conn.readC <- buf[:readed]:
		avaliable := cap(conn.readC) - len(conn.readC)
		c.writeDataConfirm(id, avaliable)
		c.logf("data: writed to channel, id: %d, size: %d\n", id, readed)
	default:
		conn.Reset()
	}
}

func (c *FrameConn) readUint64WithError(reader *bufio.Reader) (uint64, error) {
	cmd, err := binary.ReadUvarint(reader)
	c.logf("read uint64, %d, %#v, bufferd %d", cmd, err, reader.Buffered())
	return cmd, err
}

func (c *FrameConn) readUint64(reader *bufio.Reader) (uint64, bool) {
	cmd, err := binary.ReadUvarint(reader)
	c.logf("read uint64, %d, %#v, bufferd %d", cmd, err, reader.Buffered())
	if err == io.EOF {
		c.Close()
		return 0, false
	}
	if err != nil {
		c.occurError(err)
		return 0, false
	}
	return cmd, true
}

func (c *FrameConn) controlProcess(reader *bufio.Reader) {
	cmd, ok := c.readUint64(reader)
	c.logf("control: get cmd %d, %t", cmd, ok)
	if !ok {
		return
	}
	switch cmd {
	case commandDial:
		c.logf("control: dial ")
		id, ok := c.readUint64(reader)
		c.logf("id %d,%t\n ", id, ok)
		if !ok {
			return
		}
		go func() {
			select {
			case <-c.context.Done():
				return
			case c.acceptC <- id:
				c.logf("writeto accept id %d,%t\n ", id, ok)
			}
		}()
	case commandAccept:
		c.logf("control: accept ")
		id, ok := c.readUint64(reader)
		c.logf("id %d,%t ", id, ok)
		if !ok {
			return
		}
		if conn, ok := c.getConn(id); ok {
			c.logf("accepted id %d,%t ", id, ok)
			close(conn.dialC)
		}
	case commandClose:
		c.logf("control: close ")
		id, ok := c.readUint64(reader)
		c.logf("id %d,%t ", id, ok)
		if !ok {
			return
		}
		if conn, ok := c.getConn(id); ok {
			c.logf("closed id %d,%t ", id, ok)
			c.cleanConn(id)
			conn.closeConn()
		}
	case commandReset:
		c.logf("control: reset ")
		id, ok := c.readUint64(reader)
		c.logf("id %d,%t ", id, ok)
		if !ok {
			return
		}
		if conn, ok := c.getConn(id); ok {
			c.logf("resetd id %d,%t ", id, ok)
			conn.reset()
			c.cleanConn(id)
		}
	case commandDataConfirm:
		c.logf("control: data confirm ")
		id, ok := c.readUint64(reader)
		sizeu64, ok2 := c.readUint64(reader)
		c.logf("id %d,%t ", id, ok)
		if !(ok && ok2) {
			return
		}
		if conn, ok := c.getConn(id); ok {
			c.logf("confirm id %d,%t ", id, ok)
			if sizeu64 > 0 {
				select {
				case <-conn.writeAble:
				default:
					close(conn.writeAble)
				}
			} else {
				conn.writeAble = make(chan int)
			}
			c.logf("done write done %d, w: %#v", id, conn.writeDone)
			done := conn.writeDone
			conn.writeDone = make(chan int)
			select {
			case <-done:
			default:
				close(done)
			}
			c.logf("doneed write done %d, w: %#v", id, conn.writeDone)
		}
	case commandDataWindow:
		c.logf("control: data window ")
		id, ok := c.readUint64(reader)
		sizeu64, ok2 := c.readUint64(reader)
		c.logf("id %d,%t ", id, ok)
		if !(ok && ok2) {
			return
		}
		if conn, ok := c.getConn(id); ok {
			c.logf("window id %d,%t ", id, ok)
			if sizeu64 > 0 {
				select {
				case <-conn.writeAble:
				default:
					close(conn.writeAble)
				}
			} else {
				conn.writeAble = make(chan int)
			}
		}
	case commandPing:
		go func() {
			c.logf("control: ping ")
			if !c.writePong() {
				c.occurError(errors.New("unable write pong"))
			}
		}()
	case commandPong:
		go func() {
			c.logf("control: pong ")
			c.lastPong = time.Now()
		}()
	case commandTunnelClose:
		c.logf("receive tunnel close")
		c.clean()
		c.logf("finish clean and send confirm")
		c.writeTunnelCloseConfirm()
		c.cancel()
		c.logf("waiting close")
	case commandTunnelCloseConfirm:
		c.logf("received close confirm close net conn")
		close(c.close)
	case commandConnect:
		readed := 0
		c.logf("receive connect")
		id, ok := c.readUint64(reader)
		if !ok {
			return
		}
		c.logf("id %d,%t ", id, ok)
		sz, ok := c.readUint64(reader)
		if !ok {
			return
		}
		buf := make([]byte, sz)
		for {
			nr, err := reader.Read(buf[readed:])
			c.logf("addr: readed %d + %d  = %d", readed, nr, readed+nr)
			if err == io.EOF {
				c.Close()
				return
			}
			if err != nil {
				c.occurError(err)
				return
			}
			readed += nr
			if uint64(readed) >= sz {
				break
			}
		}
		conn, ok := c.getConn(id)
		if !ok {
			return
		}
		conn.writeLock.Lock()
		if conn.connectDone == nil {
			conn.connectDone = make(chan int)
		}
		conn.connectAddr = string(buf[:sz])
		close(conn.connectDone)
		conn.writeLock.Unlock()
	case commandConnectConfirm:
		readed := 0
		c.logf("receive connect")
		id, ok := c.readUint64(reader)
		if !ok {
			return
		}
		c.logf("id %d,%t ", id, ok)
		sz, ok := c.readUint64(reader)
		if !ok {
			return
		}
		var buf []byte = nil
		if sz > 0 {
			buf = make([]byte, sz)
			for {
				nr, err := reader.Read(buf[readed:])
				c.logf("addr: readed %d + %d  = %d", readed, nr, readed+nr)
				if err == io.EOF {
					c.Close()
					return
				}
				if err != nil {
					c.occurError(err)
					return
				}
				readed += nr
				if uint64(readed) >= sz {
					break
				}
			}
		}
		conn, ok := c.getConn(id)
		if !ok {
			return
		}
		if conn.connectDone == nil {
			return
		}
		var err error
		if sz > 0 {
			err = errors.New(string(buf))
			conn.Reset()
			conn.err = err
		}
		close(conn.connectDone)
	case commandInfo:
		c.logf("control: info ")
		sz, ok := c.readUint64(reader)
		c.logf("size %d\n ", sz)
		if !ok {
			return
		}
		if sz == 0 {
			return
		}
		var buf []byte = nil
		buf = make([]byte, sz)
		readed := 0
		for {
			nr, err := reader.Read(buf[readed:])
			c.logf("addr: readed %d + %d  = %d", readed, nr, readed+nr)
			if err == io.EOF {
				c.Close()
				return
			}
			if err != nil {
				c.occurError(err)
				return
			}
			readed += nr
			if uint64(readed) >= sz {
				break
			}
		}
		info := new(Info)
		err := json.Unmarshal(buf[:readed], info)
		if err != nil {
			c.logf("unmarshal info fail %s", err.Error())
			return
		}
		c.logf("readed info %#v", info)
		c.setInfo(info)
	}
}

// write

func (c *FrameConn) runWriter() {
	writer := bufio.NewWriter(c.net)

	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	c.logf("writer starting ")

	err := c.context.Err()
outfor:
	for ; err == nil; err = c.context.Err() {
		select {
		case <-c.context.Done():
			break outfor
		case d := <-c.writeC:
			c.processWrite(writer, d)
		case <-ticker.C:
			c.lastPing = time.Now()
			c.processWrite(writer, newPingData())
		}
	}
	c.logf("writer stoping")
	c.vg.Done()
	c.logf("writer stoped")
}

func (c *FrameConn) processWrite(writer *bufio.Writer, d data) {
	c.logf("writing tp:", d, d.tp())
	err := c.context.Err()
	if err != nil {
		c.occurError(err)
		return
	}
	switch d.tp() {
	case commandData:
		dd := d.(*dataData)
		if c.writeUint64(writer, dd.id) &&
			c.writeUint64(writer, uint64(len(dd.data))) {
			dd.nw, dd.err = writer.Write(dd.data)
			if dd.err != nil {
				c.occurError(dd.err)
			}
		} else {
			dd.err = c.err
		}
	case commandDataConfirm:
		dd := d.(*dataConfirmData)
		_ = c.writeUint64(writer, controlID) &&
			c.writeUint64(writer, dd.tp()) &&
			c.writeUint64(writer, dd.id) &&
			c.writeUint64(writer, uint64(dd.size))
	case commandDataWindow:
		dd := d.(*dataWindowData)
		_ = c.writeUint64(writer, controlID) &&
			c.writeUint64(writer, dd.tp()) &&
			c.writeUint64(writer, dd.id) &&
			c.writeUint64(writer, uint64(dd.size))
	case commandDial:
		dd := d.(*dialData)
		_ = c.writeUint64(writer, controlID) &&
			c.writeUint64(writer, dd.tp()) &&
			c.writeUint64(writer, dd.id)
	case commandInfo:
		dd := d.(*infoData)
		if c.writeUint64(writer, controlID) &&
			c.writeUint64(writer, dd.tp()) &&
			c.writeUint64(writer, dd.size) {
			_, err = writer.Write(dd.info)
			if err != nil {
				c.occurError(err)
			}
		}
	case commandAccept:
		dd := d.(*acceptData)
		_ = c.writeUint64(writer, controlID) &&
			c.writeUint64(writer, dd.tp()) &&
			c.writeUint64(writer, dd.id)
	case commandClose:
		dd := d.(*closeData)
		_ = c.writeUint64(writer, controlID) &&
			c.writeUint64(writer, dd.tp()) &&
			c.writeUint64(writer, dd.id)
	case commandReset:
		dd := d.(*resetData)
		_ = c.writeUint64(writer, controlID) &&
			c.writeUint64(writer, dd.tp()) &&
			c.writeUint64(writer, dd.id)
	case commandPing:
		dd := d.(*pingData)
		_ = c.writeUint64(writer, controlID) &&
			c.writeUint64(writer, dd.tp())
	case commandPong:
		dd := d.(*pongData)
		_ = c.writeUint64(writer, controlID) &&
			c.writeUint64(writer, dd.tp())
	case commandTunnelClose:
		dd := d.(*tunnelCloseData)
		_ = c.writeUint64(writer, controlID) &&
			c.writeUint64(writer, dd.tp())
	case commandTunnelCloseConfirm:
		dd := d.(*tunnelCloseConfirmData)
		_ = c.writeUint64(writer, controlID) &&
			c.writeUint64(writer, dd.tp())
		close(dd.sent)
	case commandConnect:
		dd := d.(*connectData)
		if c.writeUint64(writer, controlID) &&
			c.writeUint64(writer, dd.tp()) &&
			c.writeUint64(writer, dd.id) &&
			c.writeUint64(writer, uint64(len(dd.addr))) {
			_, err = writer.Write(dd.addr)
			if err != nil {
				dd.err = err
				c.occurError(err)
			}
		} else {
			dd.err = c.err
		}
	case commandConnectConfirm:
		dd := d.(*connectConfirmData)
		if c.writeUint64(writer, controlID) &&
			c.writeUint64(writer, dd.tp()) &&
			c.writeUint64(writer, dd.id) &&
			c.writeUint64(writer, uint64(len(dd.err))) &&
			dd.err != nil {
			_, err = writer.Write(dd.err)
			if err != nil {
				c.occurError(err)
			}
		}
	}
	err = writer.Flush()
	if err != nil {
		c.occurError(err)
		return
	}
}

func (c *FrameConn) writeDataTo(id uint64, b []byte) (int, error) {
	conn, ok := c.getConn(id)
	if !ok {
		return 0, fmt.Errorf("conn %d not exist", id)
	}
	dd := newDataData(id, b)
	done := conn.writeDone
	select {
	case c.writeC <- dd:
		c.logf("waiting write done %d, w: %#v", id, conn.writeDone)
		select {
		case <-done:
		case <-conn.context.Done():
		}
		c.logf("continue write done %d, w: %#v", id, conn.writeDone)
		return dd.nw, dd.err
	case <-c.context.Done():
		return 0, c.err
	}
}

func (c *FrameConn) writeConnect(id uint64, addr string) bool {
	conn, ok := c.getConn(id)
	if !ok {
		return false
	}
	dd := newConnectData(id, addr)
	conn.connectDone = make(chan int)
	select {
	case c.writeC <- dd:
		c.logf("waiting connect done %d, w: %#v", id, conn.writeDone)
		select {
		case <-conn.connectDone:
		case <-conn.context.Done():
		}
		c.logf("continue connect done %d, w: %#v", id, conn.writeDone)
		if conn.err != nil {
			return false
		}
		return true
	case <-c.context.Done():
		return false
	}
}
func (c *FrameConn) writeConnectConfirm(id uint64, err error) bool {
	_, ok := c.getConn(id)
	if !ok {
		return false
	}
	dd := newConnectConfirmData(id, err)
	select {
	case c.writeC <- dd:
		return true
	case <-c.context.Done():
		return false
	}
}

func (c *FrameConn) writeDataConfirm(id uint64, window int) bool {
	c.logf("writing data confirm %d", id)
	select {
	case c.writeC <- newDataConfirmData(id, window):
		c.logf("writing data confirm %d done window %d", id, window)
		return true
	case <-c.context.Done():
		c.logf("writing data confirm %d fail, window %d", id, window)
		return false
	}
}

func (c *FrameConn) writeDataWindow(id uint64, window int) bool {
	c.logf("writing data window %d", id)
	select {
	case c.writeC <- newDataWindowData(id, window):
		c.logf("writing data window %d done window %d", id, window)
		return true
	case <-c.context.Done():
		c.logf("writing data window %d fail, window %d", id, window)
		return false
	}
}
func (c *FrameConn) writeDial(id uint64) bool {
	c.logf("writing dial %d, wc :%#v", id, c.writeC)
	select {
	case c.writeC <- newDialData(id):
		c.logf("writing dial %d done", id)
		return true
	case <-c.context.Done():
		c.logf("writing dial %d fail", id)
		return false
	}
}
func (c *FrameConn) writeInfo(info *Info) bool {
	c.logf("writing info %#v", info)
	select {
	case c.writeC <- newInfoData(info):
		c.logf("writing info done")
		return true
	case <-c.context.Done():
		c.logf("writing info fail")
		return false
	}
}
func (c *FrameConn) writeAccept(id uint64) bool {
	c.logf("writing accept %d", id)
	select {
	case c.writeC <- newAcceptData(id):
		c.logf("writing accept %d done", id)
		return true
	case <-c.context.Done():
		c.logf("writing accept %d fail", id)
		return false
	}
}
func (c *FrameConn) writeClose(id uint64) bool {
	c.logf("writing close %d", id)
	select {
	case c.writeC <- newCloseData(id):
		c.logf("writing close %d done", id)
		return true
	case <-c.context.Done():
		c.logf("writing close %d fail", id)
		return false
	}
}

func (c *FrameConn) writeReset(id uint64) bool {
	c.logf("writing reset %d", id)
	select {
	case c.writeC <- newResetData(id):
		c.logf("writing reset %d done", id)
		return true
	case <-c.context.Done():
		c.logf("writing reset %d fail", id)
		return false
	}
}

func (c *FrameConn) writePong() bool {
	c.logf("writing pong")
	select {
	case c.writeC <- newPongData():
		c.logf("writing pong done")
		return true
	case <-c.context.Done():
		c.logf("writing pong fail")
		return false
	}
}
func (c *FrameConn) writeTunnelClose() bool {
	c.logf("writing tunnelClose")
	select {
	case c.writeC <- newTunnelCloseData():
		c.logf("writing tunnelClose done")
		return true
	case <-c.context.Done():
		c.logf("writing tunnelClose fail")
		return false
	}
}
func (c *FrameConn) writeTunnelCloseConfirm() bool {
	c.logf("writing tunnelCloseConfirm")
	dd := newTunnelCloseConfirmData()
	select {
	case c.writeC <- dd:
		c.logf("waitng tunnelCloseConfirm done")
		<-dd.(*tunnelCloseConfirmData).sent
		return true
	case <-c.context.Done():
		c.logf("writing tunnelCloseConfirm fail")
		return false
	}
}

func (c *FrameConn) writeUint64(w *bufio.Writer, d uint64) bool {
	c.logf("write uint64 %d", d)
	buf := make([]byte, 8)
	len := binary.PutUvarint(buf, d)
	_, err := w.Write(buf[:len])
	if err == io.EOF {
		c.Close()
		return false
	}
	if err != nil {
		c.occurError(err)
		return false
	}
	return true
}

// utils funcs

func joinCopy(name string, to, from io.ReadWriter, vg *sync.WaitGroup) error {
	defer func() {
		vg.Done()
		fmt.Printf("join copy done %s\n", name)
		if c, ok := to.(io.Closer); ok {
			fmt.Printf("%s, closing to\n", name)
			c.Close()
		}
	}()
	buf := make([]byte, 32*1024)
	for {
		nr, err := from.Read(buf)
		if err != nil {
			fmt.Printf("%s[1]: err %s\n", name, err.Error())
			return err
		}
		var writed int
		for writed < nr {
			nw, err := to.Write(buf[writed:nr])
			if err != nil {
				fmt.Printf("%s[2]: err %s\n", name, err.Error())
				return err
			}
			writed += nw
		}
	}
}

func join(c1, c2 io.ReadWriter) {
	vg := &sync.WaitGroup{}
	vg.Add(2)
	go joinCopy("target->client", c1, c2, vg)
	go joinCopy("client->target", c2, c1, vg)
	vg.Wait()
	fmt.Printf("done join\n")
}
