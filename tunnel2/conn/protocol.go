package conn

import (
	"encoding/json"
	"errors"
)

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
