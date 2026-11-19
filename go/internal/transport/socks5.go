package transport

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/url"
	"strconv"
)

// dialSOCKS5 connects through a SOCKS5/SOCKS5h proxy using RFC 1928 ATYP=0x03
// (domain name) when the target host is not an IP literal, ensuring DNS is
// resolved remotely by the proxy rather than leaking locally.
func dialSOCKS5(ctx context.Context, baseDialer *net.Dialer, proxyURL *url.URL, network, addr string) (net.Conn, error) {
	if proxyURL == nil {
		return nil, errors.New("nil SOCKS5 proxy URL")
	}
	proxyHost := proxyURL.Hostname()
	proxyPort := proxyURL.Port()
	if proxyPort == "" {
		proxyPort = "1080"
	}
	targetHost, targetPortStr, err := net.SplitHostPort(addr)
	if err != nil {
		return nil, fmt.Errorf("invalid target address %q: %w", addr, err)
	}
	targetPort, err := strconv.Atoi(targetPortStr)
	if err != nil || targetPort < 1 || targetPort > 65535 {
		return nil, fmt.Errorf("invalid target port %q", targetPortStr)
	}

	conn, err := baseDialer.DialContext(ctx, "tcp", net.JoinHostPort(proxyHost, proxyPort))
	if err != nil {
		return nil, err
	}
	if dl, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(dl)
		defer func() { _ = conn.SetDeadline(timeZero) }()
	}

	closeOnErr := true
	defer func() {
		if closeOnErr {
			_ = conn.Close()
		}
	}()

	var user, pass string
	hasAuth := false
	if proxyURL.User != nil {
		user = proxyURL.User.Username()
		pass, _ = proxyURL.User.Password()
		if len(user) > 255 || len(pass) > 255 {
			return nil, errors.New("SOCKS5 credentials exceed 255 bytes")
		}
		hasAuth = true
	}

	methods := []byte{0x00}
	if hasAuth {
		methods = []byte{0x00, 0x02}
	}
	greeting := make([]byte, 2+len(methods))
	greeting[0] = 0x05
	greeting[1] = byte(len(methods))
	copy(greeting[2:], methods)
	if _, err := conn.Write(greeting); err != nil {
		return nil, err
	}
	var methodResp [2]byte
	if _, err := io.ReadFull(conn, methodResp[:]); err != nil {
		return nil, err
	}
	if methodResp[0] != 0x05 {
		return nil, fmt.Errorf("unexpected SOCKS version %d", methodResp[0])
	}
	switch methodResp[1] {
	case 0x00:
	case 0x02:
		if !hasAuth {
			return nil, errors.New("SOCKS5 proxy requires username/password")
		}
		authMsg := make([]byte, 0, 3+len(user)+len(pass))
		authMsg = append(authMsg, 0x01, byte(len(user)))
		authMsg = append(authMsg, user...)
		authMsg = append(authMsg, byte(len(pass)))
		authMsg = append(authMsg, pass...)
		if _, err := conn.Write(authMsg); err != nil {
			return nil, err
		}
		var authResp [2]byte
		if _, err := io.ReadFull(conn, authResp[:]); err != nil {
			return nil, err
		}
		if authResp[1] != 0x00 {
			return nil, errors.New("SOCKS5 authentication failed")
		}
	default:
		return nil, fmt.Errorf("unsupported SOCKS5 auth method %d", methodResp[1])
	}

	req := make([]byte, 0, 7+len(targetHost))
	req = append(req, 0x05, 0x01, 0x00) // VER=5, CMD=CONNECT, RSV=0
	if ip := net.ParseIP(targetHost); ip != nil {
		if ip4 := ip.To4(); ip4 != nil {
			req = append(req, 0x01)
			req = append(req, ip4...)
		} else {
			req = append(req, 0x04)
			req = append(req, ip.To16()...)
		}
	} else {
		if len(targetHost) == 0 || len(targetHost) > 255 {
			return nil, fmt.Errorf("invalid SOCKS5 target host length %d", len(targetHost))
		}
		req = append(req, 0x03, byte(len(targetHost)))
		req = append(req, targetHost...)
	}
	var portBuf [2]byte
	binary.BigEndian.PutUint16(portBuf[:], uint16(targetPort))
	req = append(req, portBuf[:]...)

	if _, err := conn.Write(req); err != nil {
		return nil, err
	}
	var hdr [4]byte
	if _, err := io.ReadFull(conn, hdr[:]); err != nil {
		return nil, err
	}
	if hdr[0] != 0x05 || hdr[1] != 0x00 {
		return nil, fmt.Errorf("SOCKS5 connect failed with reply code %d", hdr[1])
	}
	var skip int
	switch hdr[3] {
	case 0x01:
		skip = 4 + 2
	case 0x04:
		skip = 16 + 2
	case 0x03:
		var ln [1]byte
		if _, err := io.ReadFull(conn, ln[:]); err != nil {
			return nil, err
		}
		skip = int(ln[0]) + 2
	default:
		return nil, fmt.Errorf("SOCKS5 invalid reply address type %d", hdr[3])
	}
	if skip > 0 {
		discard := make([]byte, skip)
		if _, err := io.ReadFull(conn, discard); err != nil {
			return nil, err
		}
	}

	closeOnErr = false
	return conn, nil
}
