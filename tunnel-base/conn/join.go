package conn

import (
	"fmt"
	"io"
	"sync"
)

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
