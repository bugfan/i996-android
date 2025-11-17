package conn

import (
	"bufio"
	"encoding/binary"
	"fmt"
	"io"
	"time"
)

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
