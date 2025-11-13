package server

import (
	"fmt"
	"math/rand"
	"strings"
	"sync"
	"time"

	"github.com/bugfan/i996-android/tunnel/conn"
)

type ConnRegistry struct {
	conns map[string]map[string]*conn.FrameConn
	sync.RWMutex
}

func NewConnRegistry() *ConnRegistry {
	return &ConnRegistry{
		conns: make(map[string]map[string]*conn.FrameConn),
	}
}
func (r *ConnRegistry) Get(clientId string) *conn.FrameConn {
	r.RLock()
	defer r.RUnlock()
	if r.conns[clientId] == nil {
		return nil
	}

	length := len(r.conns[clientId])
	if length == 0 {
		return nil
	}
	idx := rand.Int() % length
	i := 0
	for _, f := range r.conns[clientId] {
		if i == idx {
			return f
		}
		i++
	}
	return nil
}

func (r *ConnRegistry) Add(clientId string, ctl *conn.FrameConn) {
	r.Lock()
	defer r.Unlock()
	if r.conns[clientId] == nil {
		r.conns[clientId] = make(map[string]*conn.FrameConn)
	}
	r.conns[clientId][ctl.RemoteAddr().String()] = ctl
	go func() {
		for {
			if ctl.Error() != nil || ctl.RemoteIP() == "" {
				r.Del(clientId, ctl)
				return
			}
			time.Sleep(1 * time.Second)
		}
	}()
	fmt.Printf("tunnels %#v\n", r.conns)
}

func (r *ConnRegistry) Del(clientId string, ctl *conn.FrameConn) error {
	r.Lock()
	defer r.Unlock()
	if r.conns[clientId] == nil {
		return nil
	}
	found := false
	for addr, c := range r.conns[clientId] {
		if c == ctl || addr == ctl.RemoteAddr().String() {
			delete(r.conns[clientId], addr)
			found = true
		}
	}
	if found {
		go ctl.Reset()
	}
	if len(r.conns[clientId]) == 0 {
		delete(r.conns, clientId)
	}
	fmt.Printf("after delete tunnels %#v\n", r.conns)
	return nil
}

func (r *ConnRegistry) IDs() (ids map[string]string) {
	ids = make(map[string]string)
	for id, c := range r.conns {
		ips := make([]string, 0, len(c))
		for _, tunnel := range c {
			ips = append(ips, tunnel.RemoteIP())
		}
		ip := strings.Join(ips, ";")
		if ip == "" {
			ip = fmt.Sprintf("未识别隧道IP[%d]", len(r.conns))
		}
		ids[ip] = id
	}
	return
}
