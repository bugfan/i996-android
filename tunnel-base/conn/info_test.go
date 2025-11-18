package conn

import (
	"fmt"
	"testing"
)

func infoListener(t *testing.T, l *FrameListener, logging bool) {
	fc, err := l.Accept()
	fc.logging = logging
	if err != nil {
		t.Errorf("l: %s", err.Error())
		return
	}
	fc.SetInfo(&Info{
		ID: "123456",
	})
	fmt.Printf("closed %d", len(fc.conns))
}

func TestInfo(t *testing.T) {
	listen, err := Listen("127.0.0.1:12345", "tcp", nil)
	if err != nil {
		t.Errorf("b: %s", err.Error())
		return
	}
	go infoListener(t, listen, true)
	dialer, err := Dial("tcp", "127.0.0.1:12345")
	dialer.logging = true
	if err != nil {
		t.Errorf("b: %s", err.Error())
		return
	}
	info := dialer.Info()
	if info.ID != "123456" {
		t.Errorf("unexpected info %#v", info)
	}
}
