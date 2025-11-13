package client

import (
	_ "crypto/sha512"
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"io/ioutil"
	"os"
	"path"
)

const keyName = "tunnel"

func LoadTLSConfig(p string) *tls.Config {
	keyPath := func(kind string) string {
		name := fmt.Sprintf("%s.%s", keyName, kind)
		return path.Join(p, name)
	}
	rootPEM, err := ioutil.ReadFile(keyPath("crt"))
	if err != nil {
		fmt.Print("Load crt file fail\n")
		os.Exit(-1)
	}
	pool := x509.NewCertPool()
	ok := pool.AppendCertsFromPEM([]byte(rootPEM))
	if ok {
		return &tls.Config{
			RootCAs:            pool,
			InsecureSkipVerify: true,
		}
	}
	return nil
}

func LoadTLSConfigFromBytes(b []byte) *tls.Config {
	pool := x509.NewCertPool()
	ok := pool.AppendCertsFromPEM(b)
	if ok {
		return &tls.Config{
			RootCAs:            pool,
			InsecureSkipVerify: true,
		}
	}
	return nil
}
