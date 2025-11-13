package conn

import (
	"bufio"
	"fmt"
	"io"
	"runtime"
	"testing"
	"time"
)

func proxyListener(t *testing.T, l *FrameListener, logging bool) {
	fc, err := l.Accept()
	fc.logging = logging
	if err != nil {
		t.Errorf("l: %s", err.Error())
		return
	}
	for {
		conn, err := fc.Accept()
		if err == io.EOF {
			break
		}
		if err != nil {
			t.Errorf("l: %s", err.Error())
			break
		}
		go func(conn *Conn) {
			conn.Proxy()
		}(conn)
	}
	fmt.Printf("closed %d", len(fc.conns))
}

var request = "GET / HTTP/1.1\r\nHost: www.baidu.com\r\nUser-Agent: curl/7.54.0\r\nAccept: */*\r\n\r\n"

func TestProxy(t *testing.T) {
	startRoutineNum := runtime.NumGoroutine()
	listen, err := Listen("127.0.0.1:12345", "tcp", nil)
	if err != nil {
		t.Errorf("b: %s", err.Error())
		return
	}
	go proxyListener(t, listen, true)
	dialer, err := Dial("tcp", "127.0.0.1:12345")
	dialer.logging = true
	if err != nil {
		t.Errorf("b: %s", err.Error())
		return
	}

	conn, err := dialer.Dial("tcp", "www.baidu.com:80")
	if err != nil {
		t.Errorf("b: %s", err.Error())
		return
	}

	_, err = conn.Write([]byte(request))
	if err != nil {
		t.Errorf("b: %s", err.Error())
	}
	reader := bufio.NewReader(conn)
	ct, err := reader.ReadBytes('&')
	if err != nil {
		t.Errorf("b: %s", err.Error())
	}
	fmt.Printf("res %s\n", string(ct))
	conn.Close()
	time.Sleep(1 * time.Second)
	conn, err = dialer.Dial("tcp", "www.baidu.com:80")
	if err != nil {
		t.Errorf("b: %s", err.Error())
		return
	}

	_, err = conn.Write([]byte(request))
	if err != nil {
		t.Errorf("b: %s", err.Error())
	}
	reader = bufio.NewReader(conn)
	ct, err = reader.ReadBytes('&')
	if err != nil {
		t.Errorf("b: %s", err.Error())
	}
	fmt.Printf("res %s\n", string(ct))
	conn.Close()
	time.Sleep(1 * time.Second)
	endRoutineNum := runtime.NumGoroutine()
	fmt.Printf("start %d end %d\n", startRoutineNum, endRoutineNum)
	dialer.Close()
	fmt.Printf("closed end %d\n", endRoutineNum)
	p := make([]runtime.StackRecord, 100)
	count, _ := runtime.GoroutineProfile(p)
	for _, pp := range p[:count] {
		fmt.Printf("pp: %#v", pp)
	}
}
