package conn

import (
	"bufio"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"time"
)

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
