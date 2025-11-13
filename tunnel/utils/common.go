package utils

import (
	"net"
	"strconv"
	"strings"
)

func ToByteIP(ip string) []byte {
	IPv4ToNumber := func(ip string) int64 {
		bits := strings.Split(ip, ".")

		b0, _ := strconv.Atoi(bits[0])
		b1, _ := strconv.Atoi(bits[1])
		b2, _ := strconv.Atoi(bits[2])
		b3, _ := strconv.Atoi(bits[3])
		var sum int64
		sum += int64(b0) << 24
		sum += int64(b1) << 16
		sum += int64(b2) << 8
		sum += int64(b3)
		return sum
	}
	inetToa := func(ipnr int64) net.IP {
		var bytes [4]byte
		bytes[0] = byte(ipnr & 0xFF)
		bytes[1] = byte((ipnr >> 8) & 0xFF)
		bytes[2] = byte((ipnr >> 16) & 0xFF)
		bytes[3] = byte((ipnr >> 24) & 0xFF)
		return net.IPv4(bytes[3], bytes[2], bytes[1], bytes[0])
	}
	return inetToa(IPv4ToNumber(ip))
}

func IfHttpRequest(scheme string) bool {
	if scheme == "http" {
		return true
	}
	return false
}
