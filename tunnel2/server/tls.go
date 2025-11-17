package server

import (
	"crypto/tls"
	"fmt"
	"path"
)

const keyName = "tunnel"

func LoadTLSConfig(p string) (tlsConfig *tls.Config) {
	keyPath := func(kind string) string {
		name := fmt.Sprintf("%s.%s", keyName, kind)
		return path.Join(p, name)
	}
	var cert tls.Certificate
	cert, err := tls.LoadX509KeyPair(keyPath("crt"), keyPath("key"))
	if err != nil {
		return nil
	}

	return &tls.Config{
		Certificates: []tls.Certificate{cert},
	}

}

func LoadTLSConfigFromBytes(certPEM, keyPEM []byte) (tlsConfig *tls.Config) {
	cert, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		return nil
	}

	return &tls.Config{
		CipherSuites: []uint16{
			tls.TLS_RSA_WITH_AES_256_CBC_SHA,
			tls.TLS_RSA_WITH_AES_128_CBC_SHA,
			tls.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA,
			tls.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA,
			tls.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
			tls.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
			tls.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
			tls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
		},
		MinVersion:               tls.VersionTLS12,
		MaxVersion:               tls.VersionTLS12,
		PreferServerCipherSuites: true,
		Certificates:             []tls.Certificate{cert},
	}
}
