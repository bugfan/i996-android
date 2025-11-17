package conn

import (
	"fmt"
	"strconv"
	"sync"
	"testing"
)

func listenerTest(t *testing.T, l *FrameListener, vg *sync.WaitGroup, vg2 *sync.WaitGroup) {
	fc, err := l.Accept()
	fc.logging = true
	if err != nil {
		t.Error(err)
		return
	}
	conn, err := fc.Accept()
	if err != nil {
		t.Error(err)
		return
	}
	buf := make([]byte, 128)
	nr, err := conn.Read(buf)
	fmt.Println("test.server: readed")
	if err != nil {
		fmt.Print("test.server: err")
		t.Error(err)
		return
	}
	if nr != 10 {
		fmt.Print("test.server: nr")
		t.Errorf("unexpected length %d", nr)
		return
	}
	if string(buf[:nr]) != "1234567890" {
		fmt.Print("test.server: content ")
		t.Errorf("unexpected content %s", string(buf))
		return
	}
	fmt.Println("test.server: writing 987654321")
	nw, err := conn.Write([]byte("987654321"))
	fmt.Printf("test.server: write done %d, %s\n", nw, err)
	if err != nil {
		t.Error(err)
		return
	}
	if nw != 9 {
		t.Errorf("unexpected length %d", nw)
		return
	}
	// two
	fmt.Println("test.server: writing 111111111")
	nw, err = conn.Write([]byte("111111111"))
	fmt.Printf("test.server: write done %d, %s\n", nw, err)
	if err != nil {
		t.Error(err)
		return
	}
	if nw != 9 {
		t.Errorf("unexpected length %d", nw)
		return
	}

	for i := 0; i < 1000; i++ {
		buf = make([]byte, 3)
		nr, err := conn.Read(buf)
		if err != nil {
			t.Error(err)
			return
		}
		if nr < 1 {
			t.Errorf("unexpected length %d", nr)
			return
		}
		res, _ := strconv.Atoi(string(buf[:nr]))
		if res != i {
			t.Errorf("unexpected res %d != %d", res, i)
			return
		}
	}
	vg2.Done()
}

func TestFrame(t *testing.T) {
	vg := &sync.WaitGroup{}
	vg.Add(1)
	vg2 := &sync.WaitGroup{}
	vg2.Add(1)
	buf := make([]byte, 128)
	listen, err := Listen("127.0.0.1:12345", "tcp", nil)
	if err != nil {
		t.Error(err)
		return
	}
	go listenerTest(t, listen, vg, vg2)
	dialer, err := Dial("tcp", "127.0.0.1:12345")
	dialer.logging = true
	if err != nil {
		t.Error(err)
		return
	}

	fmt.Println("test.client: dial")
	conn, err := dialer.DialTunnel()
	fmt.Println("test.client: write 1234567890")
	nw, err := conn.Write([]byte("1234567890"))
	fmt.Printf("test.client: write done %d, %s\n", nw, err)
	if err != nil {
		t.Error(err)
		return
	}
	if nw != 10 {
		t.Errorf("unexpected length %d", nw)
		return
	}
	fmt.Println("test.client: reading 987654321")
	nr, err := conn.Read(buf)
	if err != nil {
		t.Error(err)
		return
	}
	if nr != 9 {
		t.Errorf("unexpected length %d", nr)
		return
	}
	if string(buf[:nr]) != "987654321" {
		t.Errorf("unexpected content %s", string(buf))
		return
	}
	fmt.Println("test.client: reading 11111111")
	nr, err = conn.Read(buf)
	if err != nil {
		t.Error(err)
		return
	}
	if nr != 9 {
		t.Errorf("unexpected length %d", nr)
		return
	}
	if string(buf[:nr]) != "111111111" {
		t.Errorf("unexpected content %s", string(buf))
		return
	}

	for i := 0; i < 1000; i++ {
		buf := []byte(strconv.Itoa(i))
		nw, err := conn.Write(buf)
		if err != nil {
			t.Error(err)
			return
		}
		if nw != len(buf) {
			t.Errorf("unexpected length %d when writing %d", nr, i)
			return
		}
	}

	conn.Close()
	vg2.Wait()
}
