package main

import (
	"crypto/tls"
	"fmt"
	"time"

	"github.com/bugfan/i996-android/tunnel/client"
)

func main() {
	tlsConfig := client.LoadTLSConfigFromBytes([]byte("cert/crrt.pem"))
	if tlsConfig != nil {
		tlsConfig.ClientSessionCache = tls.NewLRUClientSessionCache(5000)
	}
	tunnelAddr := "127.0.0.1:3333"
	for {
		c := client.NewClient(tunnelAddr, "tunnel-id-001", tlsConfig)
		fmt.Printf("server %s connectting\n", tunnelAddr)
		c.Run()
		time.Sleep(10 * time.Second)
	}
}
