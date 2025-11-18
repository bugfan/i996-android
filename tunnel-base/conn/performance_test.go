package conn

import (
	"fmt"
	"io"
	"math/rand"
	"strconv"
	"testing"
)

func echoListener(t *testing.B, l *FrameListener, logging bool) {
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
			buf := make([]byte, 128)
			for {
				nr, err := conn.Read(buf)
				if err == io.EOF {
					break
				}
				if err != nil {
					t.Errorf("l: %s", err.Error())
					break
				}
				_, err = conn.Write(buf[:nr])
				if err != nil {
					t.Errorf("l: %s", err.Error())
					break
				}
			}
		}(conn)
	}
	fmt.Printf("closed %d", len(fc.conns))
}

func BenchmarkFrameRW(t *testing.B) {
	buf := make([]byte, 128)
	listen, err := Listen("127.0.0.1:12345", "tcp", nil)
	if err != nil {
		t.Errorf("b: %s", err.Error())
		return
	}
	go echoListener(t, listen, false)
	dialer, err := Dial("tcp", "127.0.0.1:12345")
	dialer.logging = false
	if err != nil {
		t.Errorf("b: %s", err.Error())
		return
	}

	conn, err := dialer.DialTunnel()
	if err != nil {
		t.Errorf("b: %s", err.Error())
		return
	}
	t.ResetTimer()
	for i := 0; i < t.N; i++ {
		nw, err := conn.Write([]byte("1234567890"))
		if err != nil {
			t.Errorf("b: %s", err.Error())
		}
		nr, err := conn.Read(buf)
		if err != nil {
			t.Errorf("b: %s", err.Error())
		}
		if nw != nr {
			t.Error("not equal")
		}
	}
	conn.Close()
}

func BenchmarkFrameConn(t *testing.B) {
	fmt.Println("running frame conn")
	buf := make([]byte, 128)
	listen, err := Listen("127.0.0.1:12345", "tcp", nil)
	if err != nil {
		t.Errorf("b: %s", err.Error())
		return
	}
	go echoListener(t, listen, true)
	dialer, err := Dial("tcp", "127.0.0.1:12345")
	dialer.logging = true
	if err != nil {
		t.Errorf("b: %s", err.Error())
		return
	}

	t.ResetTimer()
	for i := 0; i < t.N; i++ {
		conn, err := dialer.DialTunnel()
		if err != nil {
			t.Errorf("b: %s", err.Error())
			return
		}
		data := rand.Uint64()
		str := strconv.FormatUint(data, 10)
		nw, err := conn.Write([]byte(str))
		if err != nil {
			t.Errorf("b: %s", err.Error())
		}
		nr, err := conn.Read(buf)
		if err != nil {
			t.Errorf("b: %s", err.Error())
		}
		if nw != nr {
			t.Error("not equal")
		}
		if string(buf[:nr]) != str {
			t.Errorf("id %d, not equal, buf %s, str %s", conn.id, string(buf[:nr]), str)
		}
		conn.Close()
	}
	fmt.Printf("closing dialer\n")
	dialer.Close()
}

func BenchmarkFrameMulti(t *testing.B) {
	fmt.Println("running frame conn multi")
	listen, err := Listen("127.0.0.1:12345", "tcp", nil)
	if err != nil {
		t.Errorf("b: %s", err.Error())
		return
	}
	go echoListener(t, listen, true)
	dialer, err := Dial("tcp", "127.0.0.1:12345")
	dialer.logging = true
	if err != nil {
		t.Errorf("b: %s", err.Error())
		return
	}

	t.ResetTimer()
	t.RunParallel(func(pb *testing.PB) {
		buf := make([]byte, 128)
		for pb.Next() {
			conn, err := dialer.DialTunnel()
			if err != nil {
				t.Errorf("b: %s", err.Error())
				return
			}
			data := rand.Uint64()
			str := strconv.FormatUint(data, 10)
			nw, err := conn.Write([]byte(str))
			if err != nil {
				t.Errorf("b: %s", err.Error())
			}
			nr, err := conn.Read(buf)
			if err != nil {
				t.Errorf("b: %s", err.Error())
			}
			if nw != nr {
				t.Error("not equal")
			}
			if string(buf[:nr]) != str {
				t.Errorf("id %d, not equal, buf %s, str %s", conn.id, string(buf[:nr]), str)
			}
			conn.Close()
		}
	})
	fmt.Printf("conns %d\n", len(dialer.conns))
	dialer.Close()
}
