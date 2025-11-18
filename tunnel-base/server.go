package main

import (
	"crypto/tls"
	"fmt"
	"log"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"

	"github.com/bugfan/i996-android/tunnel/server"
)

const (
	tid = "tunnel-id-001"
)

func main() {

	// http server
	go httpserver()

	// tunnel server
	keyPEM, err := os.ReadFile("cert/key.pem")
	if err != nil {
		fmt.Println("failed to read key.pem:", err)
		return
	}
	certPEM, err := os.ReadFile("cert/cert.pem")
	if err != nil {
		fmt.Println("failed to read cert.pem:", err)
		return
	}
	tlsConf := server.LoadTLSConfigFromBytes(certPEM, keyPEM)
	if tlsConf != nil {
		tlsConf.ClientSessionCache = tls.NewLRUClientSessionCache(5000)
	}
	server.ListenTunnel(":3333", tlsConf)
}

func httpserver() {
	handler := func(w http.ResponseWriter, r *http.Request) {
		// 动态决定目标
		targetURL, err := url.Parse("https://www.json.cn")
		if err != nil {
			http.Error(w, "invalid target", http.StatusInternalServerError)
			return
		}

		transport, _ := server.GetHTTPTransport(tid, "", "")

		// 创建反向代理
		proxy := httputil.NewSingleHostReverseProxy(targetURL)

		// 动态修改底层 transport
		proxy.Transport = transport

		// 你也可以在这里改 Header、日志、注入认证信息等
		proxy.ModifyResponse = func(resp *http.Response) error {
			resp.Header.Set("X-Proxy-By", "GoDynamicProxy")
			return nil
		}

		// 转发
		proxy.ServeHTTP(w, r)
	}

	srv := &http.Server{
		Addr:    ":4444",
		Handler: http.HandlerFunc(handler),
	}

	log.Println("Reverse proxy listening on :4444 (→ https://www.json.cn)")
	log.Fatal(srv.ListenAndServe())
}
