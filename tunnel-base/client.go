package main

import (
	"crypto/tls"
	"fmt"
	"os"
	"time"

	"github.com/bugfan/i996-android/tunnel/client"
)

func main() {
	certPEM, err := os.ReadFile("cert/cert.pem")
	if err != nil {
		fmt.Println("failed to read cert.pem:", err)
		return
	}
	tlsConfig := client.LoadTLSConfigFromBytes(certPEM)
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
