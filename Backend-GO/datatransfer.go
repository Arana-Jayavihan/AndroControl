package main

import (
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"sync"
	"time"
)

const (
	rolePhone = "phone"
	roleAgent = "agent"
	// bulkPairTimeout drops a bulk endpoint that never gets a partner, so a half-open
	// transfer channel can't linger.
	bulkPairTimeout = 60 * time.Second
)

// dataBridge couples the phone's bulk data connection (the data port, mTLS) with the
// desktop agent's bulk loopback sub-channel, and streams bytes between them. The
// file-transfer protocol (offer / accept / chunks / zip) is end-to-end between the phone
// and the agent — the server only shuffles bytes, so it never buffers whole files and
// big transfers stay off the input/control stream entirely.
type dataBridge struct {
	mu     sync.Mutex
	phone  net.Conn
	agent  net.Conn
	piping bool
}

var dataBridgeInst = &dataBridge{}

// isPiping reports whether a transfer is actively bridged (both endpoints linked).
func (b *dataBridge) isPiping() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.piping
}

// register stores a bulk endpoint for its role and, once both are present, starts piping.
// The bridge takes ownership of the connection (it closes it); callers must not.
func (b *dataBridge) register(role string, conn net.Conn) {
	b.mu.Lock()
	switch role {
	case rolePhone:
		if b.phone != nil {
			b.phone.Close()
		}
		b.phone = conn
	case roleAgent:
		if b.agent != nil {
			b.agent.Close()
		}
		b.agent = conn
	}
	phone, agent := b.phone, b.agent
	start := phone != nil && agent != nil && !b.piping
	if start {
		b.piping = true
	}
	b.mu.Unlock()

	if start {
		go b.pipe(phone, agent)
	} else {
		go b.reapIfUnpaired(role, conn)
	}
}

func (b *dataBridge) reapIfUnpaired(role string, conn net.Conn) {
	time.Sleep(bulkPairTimeout)
	b.mu.Lock()
	unpaired := !b.piping &&
		((role == rolePhone && b.phone == conn) || (role == roleAgent && b.agent == conn))
	if unpaired {
		if role == rolePhone {
			b.phone = nil
		} else {
			b.agent = nil
		}
	}
	b.mu.Unlock()
	if unpaired {
		logDebug("data bridge: %s endpoint unpaired after %s, closing", role, bulkPairTimeout)
		conn.Close()
	}
}

func (b *dataBridge) pipe(phone, agent net.Conn) {
	logInfo("data bridge: linked (file transfer ready)")
	done := make(chan struct{}, 2)
	go func() { io.Copy(agent, phone); done <- struct{}{} }()
	go func() { io.Copy(phone, agent); done <- struct{}{} }()
	<-done

	phone.Close()
	agent.Close()

	b.mu.Lock()
	if b.phone == phone {
		b.phone = nil
	}
	if b.agent == agent {
		b.agent = nil
	}
	b.piping = false
	b.mu.Unlock()
	logInfo("data bridge: unlinked")
}

// startDataListener accepts the phone's bulk data connection over mTLS on its own port,
// authenticates it by client certificate (a known paired device), then hands it to the
// bridge. Kept separate from the control port so file bytes never share the input stream.
func startDataListener(addr string, port int, tlsCfg *tls.Config, stop <-chan struct{}) {
	ln, err := tls.Listen("tcp", fmt.Sprintf("%s:%d", addr, port), tlsCfg)
	if err != nil {
		logError("data transfer: failed to listen on %s:%d: %v", addr, port, err)
		return
	}
	logInfo("data transfer: listening on %s:%d (TLS)", addr, port)
	go func() { <-stop; ln.Close() }()

	for {
		conn, err := ln.Accept()
		if err != nil {
			return // listener closed on shutdown
		}
		go handleDataConn(conn)
	}
}

func handleDataConn(conn net.Conn) {
	clientIP := extractIP(conn.RemoteAddr())
	tlsConn, ok := conn.(*tls.Conn)
	if !ok {
		conn.Close()
		return
	}
	tlsConn.SetDeadline(time.Now().Add(HandshakeTimeout))
	if err := tlsConn.Handshake(); err != nil {
		logWarn("data transfer: TLS handshake failed from %s: %v", clientIP, err)
		conn.Close()
		return
	}
	state := tlsConn.ConnectionState()
	if len(state.PeerCertificates) == 0 {
		logWarn("data transfer: no client certificate from %s", clientIP)
		conn.Close()
		return
	}
	certFP := CertFingerprintHex(state.PeerCertificates[0])
	device := deviceManager.ValidateCert(certFP)
	if device == nil {
		logWarn("data transfer: unknown device certificate from %s", clientIP)
		conn.Close()
		return
	}
	tlsConn.SetDeadline(time.Time{})
	if _, err := conn.Write([]byte("DATA:OK\n")); err != nil {
		conn.Close()
		return
	}
	logInfo("data transfer: phone bulk channel from device=%q ip=%s", device.Name, clientIP)
	dataBridgeInst.register(rolePhone, conn)
}
