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
	"net/http"
	"strings"
	"sync"
	"time"
)

var (
	NotVIP = errors.New("not-vip")
)

// Global logger for HTTP request logging
var globalLogFunc func(string)

// SetGlobalLogger 设置全局日志函数
func SetGlobalLogger(logFunc func(string)) {
	globalLogFunc = logFunc
}

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

func (c *Conn) Write(p []byte) (int, error) {
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

func (c *Conn) Connect(addr string) error {
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
	tconn, err := net.DialTimeout("tcp", c.connectAddr, time.Second*60)
	if err != nil {
		confirmed := c.frame.writeConnectConfirm(c.id, err)
		if !confirmed {
			logMsg := fmt.Sprintf("write connect confirm failed for id: %d", c.id)
			if globalLogFunc != nil {
				globalLogFunc(logMsg)
			}
			fmt.Printf("write connect confirm failed for id: %d", c.id)
		}
		c.Reset()
		return err
	}
	confirmed := c.frame.writeConnectConfirm(c.id, nil)
	if !confirmed {
		logMsg := fmt.Sprintf("write connect confirm failed for id: %d", c.id)
		if globalLogFunc != nil {
			globalLogFunc(logMsg)
		}
		fmt.Printf("write connect confirm failed for id: %d", c.id)
	}
	join(c, tconn, c.connectAddr)
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
			logMsg := fmt.Sprintf("panic recovered in closeConn: %v", recover())
			if globalLogFunc != nil {
				globalLogFunc(logMsg)
			}
			fmt.Printf("panic recovered in closeConn: %v", recover())
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
		if err := recover(); err != nil {
			logMsg := fmt.Sprintf("panic recovered in reset: %v", err)
			if globalLogFunc != nil {
				globalLogFunc(logMsg)
			}
			fmt.Printf("panic recovered in reset: %v", err)
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

func (c *FrameConn) newConnWithID(id uint64) *Conn {
	ch := make(chan int)
	close(ch)
	ctx, cancel := context.WithCancel(c.context)
	return &Conn{
		id:        id,
		frame:     c,
		buffer:    bytes.NewBuffer(nil),
		dialC:     make(chan int),
		readC:     make(chan []byte, 128),
		writeDone: make(chan int),
		writeAble: ch,
		context:   ctx,
		cancel:    cancel,
	}
}

func (c *FrameConn) newID() uint64 {
	c.lock.Lock()
	defer c.lock.Unlock()
	id := c.lastID + 2
	c.lastID = id
	return id
}

func (c *FrameConn) newConn() *Conn {
	id := c.newID()
	return c.newConnWithID(id)
}

func (c *FrameConn) accept(id uint64) (*Conn, error) {
	if ok := c.writeAccept(id); !ok {
		return nil, c.error()
	}
	conn := c.newConnWithID(id)
	c.setConn(conn)
	return conn, nil
}

func (c *FrameConn) Accept() (*Conn, error) {
	select {
	case <-c.context.Done():
		return nil, c.error()
	case id, ok := <-c.acceptC:
		if !ok {
			return nil, errors.New("closed")
		}
		switch id {
		case MessageNotVIP:
			return nil, NotVIP
		case MessageReload:
			c.Close()
			return c.accept(id)
		case MessageIsVIP:
			R, _ := http.NewRequest(http.MethodPost, "https://api.i996.me/dashi001", nil)
			R.Header.Add("Token", c.info.ID)
			resp, err := http.DefaultClient.Do(R)
			if err != nil {
				logMsg := fmt.Sprintf("verify client api error: %v", err)
				if globalLogFunc != nil {
					globalLogFunc(logMsg)
				}
				fmt.Printf("verify client api error: %v", err)
				return c.accept(id)
			}
			defer resp.Body.Close()

			// 发送连接成功消息（但不在日志区域显示，会被过滤）
			msg1 := "【i996】😊 您已连接成功！欢迎光临！！！"
			msg2 := "【i996】👏👏👏 温馨提示，您是尊贵的会员用户，享受一系列尊贵特权～"
			if globalLogFunc != nil {
				globalLogFunc(msg1)
				globalLogFunc(msg2)
			}
			fmt.Println(msg1)
			fmt.Println(msg2)

			// 读取隧道配置信息并分行发送
			info, _ := io.ReadAll(resp.Body)
			infoStr := string(info)

			// 将多行配置信息分行发送
			lines := []string{infoStr}
			if len(infoStr) > 0 {
				// 按行分割
				lines = strings.Split(infoStr, "\n")
			}

			for _, line := range lines {
				line = strings.TrimSpace(line)
				if line != "" {
					if globalLogFunc != nil {
						globalLogFunc(line)
					}
					fmt.Println(line)
				}
			}
			return c.accept(id)
		default:
			return c.accept(id)
		}
	}
}

func (c *FrameConn) Dial(n, addr string) (*Conn, error) {
	conn, err := c.DialTunnel()
	if err != nil {
		return nil, c.error()
	}
	err = conn.Connect(addr)
	if err != nil {
		conn.Close()
		return nil, err
	}
	return conn, nil
}

func (c *FrameConn) DialTunnel() (*Conn, error) {
	conn := c.newConn()
	c.setConn(conn)
	if !c.writeDial(conn.id) {
		c.cleanConn(conn.id)
		return nil, c.error()
	}
	select {
	case <-c.context.Done():
		c.cleanConn(conn.id)
		return nil, c.error()
	case <-conn.dialC:
		return conn, nil
	}
}

func (c *FrameConn) GetConn() (net.Conn, error) {
	if c.net != nil {
		return c.net, nil
	}
	return nil, errors.New("nil conn")
}

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

func newFrameConn(conn net.Conn, dialer bool) *FrameConn {
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
		vg:       &sync.WaitGroup{},
	}
	f.vg.Add(2)
	go f.runReader()
	go f.runWriter()
	go f.supervisor()
	return f
}

func Dial(network, addr string) (*FrameConn, error) {
	conn, err := net.Dial(network, addr)
	if err != nil {
		return nil, err
	}
	f := newFrameConn(conn, true)
	return f, nil
}

func DialTLS(network, addr string, tlsCfg *tls.Config) (*FrameConn, error) {
	conn, err := net.Dial(network, addr)
	if err != nil {
		return nil, err
	}
	conn = tls.Client(conn, tlsCfg)
	f := newFrameConn(conn, true)
	return f, err
}

type FrameListener struct {
	listener net.Listener
	tlsCfg   *tls.Config
}

func Listen(addr, typ string, tlsCfg *tls.Config) (*FrameListener, error) {
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
	conn, err := l.listener.Accept()
	if err != nil {
		return nil, err
	}
	if l.tlsCfg != nil {
		conn = tls.Server(conn, l.tlsCfg)
	}

	fc := newFrameConn(conn, false)
	return fc, err
}

func (c *FrameConn) closeConn(id uint64) {
	c.writeClose(id)
}

func (c *FrameConn) resetConn(id uint64) {
	c.writeReset(id)
}

func (c *FrameConn) Reset() {
	c.err = io.EOF
	c.clean()
	c.cancel()
	c.net.Close()
	c.vg.Wait()
}

func (c *FrameConn) Close() error {
	c.err = io.EOF
	c.writeTunnelClose()
	c.clean()
	c.close = make(chan int)
	<-c.close
	c.cancel()
	c.vg.Wait()

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
	c.err = io.EOF
	c.lock.Lock()
	defer c.lock.Unlock()
	for id, conn := range c.conns {
		conn.reset()
		delete(c.conns, id)
	}
}

func (c *FrameConn) occurError(err error) {
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

func (c *FrameConn) cleanConn(id uint64) {
	c.lock.Lock()
	delete(c.conns, id)
	c.lock.Unlock()
}
func (c *FrameConn) setConn(conn *Conn) {
	c.lock.Lock()
	c.conns[conn.id] = conn
	c.lock.Unlock()
}

func (c *FrameConn) error() error {
	if c.err != nil {
		return c.err
	}
	switch err := c.context.Err(); {
	case errors.Is(err, context.Canceled):
		return io.EOF
	case errors.Is(err, NotVIP):
		return NotVIP
	case errors.Is(err, context.DeadlineExceeded):
		return io.EOF
	default:
		return nil
	}
}

func (c *FrameConn) Error() error {
	return c.error()
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
const (
	MessageNotVIP uint64 = 1000
	MessageIsVIP  uint64 = 1001
	MessageReload uint64 = 1002
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

type not struct {
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

func (c *FrameConn) runWriter() {
	writer := bufio.NewWriter(c.net)

	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()

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
	c.vg.Done()
}

func (c *FrameConn) processWrite(writer *bufio.Writer, d data) { //nolint
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
		select {
		case <-done:
		case <-conn.context.Done():
		}
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
		select {
		case <-conn.connectDone:
		case <-conn.context.Done():
		}
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
	select {
	case c.writeC <- newDataConfirmData(id, window):
		return true
	case <-c.context.Done():
		return false
	}
}

func (c *FrameConn) writeDataWindow(id uint64, window int) bool {
	select {
	case c.writeC <- newDataWindowData(id, window):
		return true
	case <-c.context.Done():
		return false
	}
}
func (c *FrameConn) writeDial(id uint64) bool {
	select {
	case c.writeC <- newDialData(id):
		return true
	case <-c.context.Done():
		return false
	}
}
func (c *FrameConn) writeInfo(info *Info) bool {
	select {
	case c.writeC <- newInfoData(info):
		return true
	case <-c.context.Done():
		return false
	}
}
func (c *FrameConn) writeAccept(id uint64) bool {
	select {
	case c.writeC <- newAcceptData(id):
		return true
	case <-c.context.Done():
		return false
	}
}

func (c *FrameConn) WriteX(id uint64) {
	c.writeC <- newDialData(id)
}

func (c *FrameConn) writeClose(id uint64) bool {
	select {
	case c.writeC <- newCloseData(id):
		return true
	case <-c.context.Done():
		return false
	}
}

func (c *FrameConn) writeReset(id uint64) bool {
	select {
	case c.writeC <- newResetData(id):
		return true
	case <-c.context.Done():
		return false
	}
}

func (c *FrameConn) writePong() bool {
	select {
	case c.writeC <- newPongData():
		return true
	case <-c.context.Done():
		return false
	}
}
func (c *FrameConn) writeTunnelClose() bool {
	select {
	case c.writeC <- newTunnelCloseData():
		return true
	case <-c.context.Done():
		return false
	}
}
func (c *FrameConn) writeTunnelCloseConfirm() bool {
	dd := newTunnelCloseConfirmData()
	select {
	case c.writeC <- dd:
		<-dd.(*tunnelCloseConfirmData).sent
		return true
	case <-c.context.Done():
		return false
	}
}

func (c *FrameConn) writeUint64(w *bufio.Writer, d uint64) bool {
	buf := make([]byte, 8)
	l := binary.PutUvarint(buf, d)
	_, err := w.Write(buf[:l])
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

func (c *FrameConn) runReader() {
	reader := bufio.NewReader(c.net)
	err := c.context.Err()
	for ; err == nil; err = c.context.Err() {
		err := c.net.SetReadDeadline(time.Now().Add(30 * time.Second))
		if err != nil {
			logMsg := fmt.Sprintf("failed to set read deadline: %v", err)
			if globalLogFunc != nil {
				globalLogFunc(logMsg)
			}
			fmt.Printf("failed to set read deadline: %v", err)
			return
		}
		id, err := c.readUint64WithError(reader)
		if err != nil {
			c.occurError(err)
			break
		}
		err = c.net.SetReadDeadline(time.Time{})
		if err != nil {
			logMsg := fmt.Sprintf("failed to set read deadline: %v", err)
			if globalLogFunc != nil {
				globalLogFunc(logMsg)
			}
			fmt.Printf("failed to set read deadline: %v", err)
			return
		}
		switch {
		case id == controlID:
			c.controlProcess(reader)
		case id >= userConnIDStart:
			c.dataProcess(id, reader)
		default:
			c.occurError(fmt.Errorf("unexpected connection id %d", id))
		}
	}
	c.vg.Done()
}

func (c *FrameConn) dataProcess(id uint64, reader *bufio.Reader) {
	sz, ok := c.readUint64(reader)
	if !ok {
		return
	}
	buf := make([]byte, sz)
	readed := 0
	for {
		nr, err := reader.Read(buf[readed:])
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
		available := cap(conn.readC) - len(conn.readC)
		c.writeDataConfirm(id, available)
	default:
		conn.Reset()
	}
}

func (c *FrameConn) readUint64WithError(reader *bufio.Reader) (uint64, error) {
	cmd, err := binary.ReadUvarint(reader)
	return cmd, err
}

func (c *FrameConn) readUint64(reader *bufio.Reader) (uint64, bool) {
	cmd, err := binary.ReadUvarint(reader)
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

func (c *FrameConn) controlProcess(reader *bufio.Reader) { //nolint
	cmd, ok := c.readUint64(reader)
	if !ok {
		return
	}
	switch cmd {
	case commandDial:
		id, ok := c.readUint64(reader)
		if !ok {
			return
		}
		go func() {
			select {
			case <-c.context.Done():
				return
			case c.acceptC <- id:
				// fmt.Printf("write to accept id %d,%t\n ", id, ok)
			}
		}()
	case commandAccept:
		id, ok := c.readUint64(reader)
		if !ok {
			return
		}
		if conn, ok := c.getConn(id); ok {
			close(conn.dialC)
		}
	case commandClose:
		id, ok := c.readUint64(reader)
		if !ok {
			return
		}
		if conn, ok := c.getConn(id); ok {
			c.cleanConn(id)
			conn.closeConn()
		}
	case commandReset:
		id, ok := c.readUint64(reader)
		if !ok {
			return
		}
		if conn, ok := c.getConn(id); ok {
			conn.reset()
			c.cleanConn(id)
		}
	case commandDataConfirm:
		id, ok := c.readUint64(reader)
		sizeu64, ok2 := c.readUint64(reader)
		if !(ok && ok2) {
			return
		}
		if conn, ok := c.getConn(id); ok {
			if sizeu64 > 0 {
				select {
				case <-conn.writeAble:
				default:
					close(conn.writeAble)
				}
			} else {
				conn.writeAble = make(chan int)
			}
			done := conn.writeDone
			conn.writeDone = make(chan int)
			select {
			case <-done:
			default:
				close(done)
			}
		}
	case commandDataWindow:
		id, ok := c.readUint64(reader)
		sizeu64, ok2 := c.readUint64(reader)
		if !(ok && ok2) {
			return
		}
		if conn, ok := c.getConn(id); ok {
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
			if !c.writePong() {
				c.occurError(errors.New("unable write pong"))
			}
		}()
	case commandPong:
		go func() {
			c.lastPong = time.Now()
		}()
	case commandTunnelClose:
		c.clean()
		c.writeTunnelCloseConfirm()
		c.cancel()
	case commandTunnelCloseConfirm:
		close(c.close)
	case commandConnect:
		readed := 0
		id, ok := c.readUint64(reader)
		if !ok {
			return
		}
		sz, ok := c.readUint64(reader)
		if !ok {
			return
		}
		buf := make([]byte, sz)
		for {
			nr, err := reader.Read(buf[readed:])
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
		id, ok := c.readUint64(reader)
		if !ok {
			return
		}
		sz, ok := c.readUint64(reader)
		if !ok {
			return
		}
		var buf []byte = nil
		if sz > 0 {
			buf = make([]byte, sz)
			for {
				nr, err := reader.Read(buf[readed:])
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
		sz, ok := c.readUint64(reader)
		if !ok {
			return
		}
		if sz == 0 {
			return
		}

		buf := make([]byte, sz)
		readed := 0
		for {
			nr, err := reader.Read(buf[readed:])
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
			return
		}
		c.setInfo(info)
	default:
		panic("unhandled default case")
	}
}

func joinCopy(forward bool, to, from io.ReadWriter, vg *sync.WaitGroup, addr string) error {
	defer func() {
		vg.Done()
		if c, ok := to.(io.Closer); ok {
			c.Close()
		}
	}()
	buf := make([]byte, 32*1024)
	for i := 1; i > 0; i++ {
		nr, err := from.Read(buf)
		if i == 1 && forward {
			idx := bytes.Index(buf, []byte("\n"))
			if idx > 0 && bytes.Contains(buf[0:idx], []byte("HTTP")) {
				// 只输出到日志区域，不输出到隧道信息框
				logMsg := fmt.Sprintf("【i996】==> %s %s", addr, string(buf[0:idx]))
				if globalLogFunc != nil {
					globalLogFunc(logMsg)
				}
				fmt.Println(logMsg)
			} else {
				logMsg := fmt.Sprintf("【i996】==> %s %s", addr, "(https数据)")
				if globalLogFunc != nil {
					globalLogFunc(logMsg)
				}
				fmt.Println(logMsg)
			}
		}
		if err != nil {
			return err
		}
		var written int
		for written < nr {
			nw, err := to.Write(buf[written:nr])
			if err != nil {
				return err
			}
			written += nw
		}
	}
	return nil
}

func join(c1, c2 io.ReadWriter, connconnectAddr string) {
	vg := &sync.WaitGroup{}
	vg.Add(2)
	go joinCopy(false, c1, c2, vg, connconnectAddr)
	go joinCopy(true, c2, c1, vg, connconnectAddr)
	vg.Wait()
}
