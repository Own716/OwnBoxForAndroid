package snell

import (
	"bufio"
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"fmt"
	"io"
	mathrand "math/rand/v2"
	"net"
	"strings"
	"sync"
	"time"

	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
)

type ObfsMode int

const (
	ObfsModeNone ObfsMode = iota
	ObfsModeHTTP
	ObfsModeTLS
)

const (
	// Surge 6.4.4 (10661): -[SNObfsHelperHTTP initWithPolicy:] and -[SGObfsHelperTLS initWithPolicy:]
	// substitute these constants when the policy carries no obfs-host / obfs-uri.
	DefaultObfsHost    = "bing.com"
	DefaultObfsURI     = "/"
	DefaultTLSObfsHost = "cloudfront.net"
)

const (
	// Surge 6.4.4 (10661): -[SGObfsHelperTLS encodeObfsData:] carries at most 0x400 bytes in the
	// ClientHello session ticket extension and splits everything after it into 0x4000 byte records.
	tlsObfsClientHelloPayloadLen = 0x400
	tlsObfsRecordPayloadLen      = 0x4000

	// Surge 6.4.4 (10661): -[SGObfsHelperTLS encodeObfsData:] allocates hostname + payload + 0xd9
	// bytes for the ClientHello: the payload starts at 0x8e, behind it come the 9 byte server name
	// extension header, the hostname and a fixed 0x42 byte trailer. The three length fields count
	// from the end of their own prefix.
	tlsObfsClientHelloOverhead      = 0xd9
	tlsObfsClientHelloPayloadOffset = 0x8e
	tlsObfsClientHelloNameHeaderLen = 9
	tlsObfsClientHelloTrailerLen    = 0x42
	tlsObfsClientRecordOverhead     = 0xd4
	tlsObfsClientHandshakeOverhead  = 0xd0
	tlsObfsClientExtensionsOverhead = 0x4f

	// Surge 6.4.4 (10661): -[SGObfsHelperTLS decodeObfsData:outData:] reads the first response
	// payload length at 0x69 and the payload itself at 0x6b.
	tlsObfsServerHelloPayloadOffset = 0x6b

	tlsObfsRecordHeaderLen = 5
)

type ObfsConfig struct {
	Mode ObfsMode
	Host string
	URI  string
}

func ParseObfsMode(name string) (ObfsMode, error) {
	switch strings.ToLower(name) {
	case "", "none":
		return ObfsModeNone, nil
	case "http":
		return ObfsModeHTTP, nil
	case "tls":
		return ObfsModeTLS, nil
	default:
		return 0, E.New("snell: unknown obfs mode: ", name)
	}
}

func (m ObfsMode) String() string {
	switch m {
	case ObfsModeNone:
		return "none"
	case ObfsModeHTTP:
		return "http"
	case ObfsModeTLS:
		return "tls"
	default:
		panic("snell: invalid obfs mode")
	}
}

func (c ObfsConfig) ClientConn(conn net.Conn) net.Conn {
	switch c.Mode {
	case ObfsModeNone:
		return conn
	case ObfsModeHTTP:
		return &httpObfsClientConn{Conn: conn, config: c, head: httpObfsHead{upstream: conn}}
	case ObfsModeTLS:
		return &tlsObfsClientConn{tlsObfsRecords: tlsObfsRecords{Conn: conn}, config: c}
	default:
		panic("snell: invalid obfs mode")
	}
}

func (c ObfsConfig) ServerConn(conn net.Conn) net.Conn {
	switch c.Mode {
	case ObfsModeNone:
		return conn
	case ObfsModeHTTP:
		return &httpObfsServerConn{Conn: conn, head: httpObfsHead{upstream: conn}}
	case ObfsModeTLS:
		return &tlsObfsServerConn{tlsObfsRecords: tlsObfsRecords{Conn: conn}}
	default:
		panic("snell: invalid obfs mode")
	}
}

type httpObfsFingerprint struct {
	userAgent string
	key       string
}

// Surge 6.4.4 (10661): the dispatch_once block of -[SNObfsHelperHTTP encodeObfsData:] derives one
// WebSocket key and one Firefox user agent for the whole process, picking 10.9 to 10.14 and
// Firefox 22 to 64.
var httpObfsClientFingerprint = sync.OnceValue(func() httpObfsFingerprint {
	var key [16]byte
	common.Must1(rand.Read(key[:]))
	return httpObfsFingerprint{
		userAgent: fmt.Sprintf("Mozilla/5.0 (Macintosh; Intel Mac OS X 10.%d; rv:64.0) Gecko/20100101 Firefox/%d.0", 9+mathrand.IntN(6), 22+mathrand.IntN(43)),
		key:       base64.StdEncoding.EncodeToString(key[:]),
	}
})

// Surge 6.4.4 (10661): the dispatch_once block of -[SNObfsHelperServer encodeObfsData:] builds one
// fake 101 response for the whole process and dates it with localtime_r while labelling it GMT.
var httpObfsServerResponse = sync.OnceValue(func() []byte {
	var accept [16]byte
	common.Must1(rand.Read(accept[:]))
	return fmt.Appendf(nil, "HTTP/1.1 101 Switching Protocols\r\nServer: nginx/1.%d.%d\r\nDate: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: %s\r\n\r\n",
		mathrand.IntN(14), mathrand.IntN(12), time.Now().Format("Mon, 02 Jan 2006 15:04:05 GMT"), base64.StdEncoding.EncodeToString(accept[:]))
})

// Surge 6.4.4 (10661): -[SNObfsHelperHTTP decodeObfsData:outData:] and
// -[SNObfsHelperServer decodeObfsData:outData:] both drop everything up to and including the first
// \r\n\r\n and never look at the request line, the status line or any header.
type httpObfsHead struct {
	upstream net.Conn
	access   sync.Mutex
	reader   *bufio.Reader
}

func (h *httpObfsHead) Read(p []byte) (int, error) {
	h.access.Lock()
	if h.reader == nil {
		reader := bufio.NewReader(h.upstream)
		const terminator = "\r\n\r\n"
		matched := 0
		for matched < len(terminator) {
			value, err := reader.ReadByte()
			if err != nil {
				h.access.Unlock()
				return 0, E.Cause(err, "read http obfs head")
			}
			switch value {
			case terminator[matched]:
				matched++
			case terminator[0]:
				matched = 1
			default:
				matched = 0
			}
		}
		h.reader = reader
	}
	reader := h.reader
	h.access.Unlock()
	return reader.Read(p)
}

type httpObfsClientConn struct {
	net.Conn
	config      ObfsConfig
	head        httpObfsHead
	writeAccess sync.Mutex
	requestSent bool
}

func (c *httpObfsClientConn) Read(p []byte) (int, error) {
	return c.head.Read(p)
}

func (c *httpObfsClientConn) Write(p []byte) (int, error) {
	c.writeAccess.Lock()
	defer c.writeAccess.Unlock()
	if c.requestSent {
		return c.Conn.Write(p)
	}
	c.requestSent = true
	host := c.config.Host
	if host == "" {
		host = DefaultObfsHost
	}
	uri := c.config.URI
	if uri == "" {
		uri = DefaultObfsURI
	}
	fingerprint := httpObfsClientFingerprint()
	// Surge 6.4.4 (10661): -[SNObfsHelperHTTP encodeObfsData:] sends this request once, with the
	// whole first packet as its body, and passes everything after it through untouched.
	request := fmt.Appendf(nil, "GET %s HTTP/1.1\r\nHost: %s\r\nUser-Agent: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nContent-Length: %d\r\nSec-WebSocket-Key: %s\r\n\r\n",
		uri, host, fingerprint.userAgent, len(p), fingerprint.key)
	request = append(request, p...)
	_, err := c.Conn.Write(request)
	if err != nil {
		return 0, E.Cause(err, "write http obfs request")
	}
	return len(p), nil
}

type httpObfsServerConn struct {
	net.Conn
	head         httpObfsHead
	writeAccess  sync.Mutex
	responseSent bool
}

func (c *httpObfsServerConn) Read(p []byte) (int, error) {
	return c.head.Read(p)
}

func (c *httpObfsServerConn) Write(p []byte) (int, error) {
	c.writeAccess.Lock()
	defer c.writeAccess.Unlock()
	if c.responseSent {
		return c.Conn.Write(p)
	}
	c.responseSent = true
	fakeResponse := httpObfsServerResponse()
	response := make([]byte, 0, len(fakeResponse)+len(p))
	response = append(append(response, fakeResponse...), p...)
	_, err := c.Conn.Write(response)
	if err != nil {
		return 0, E.Cause(err, "write http obfs response")
	}
	return len(p), nil
}

// tlsObfsRecords carries the application_data framing that both directions use once their first
// message has been exchanged.
type tlsObfsRecords struct {
	net.Conn
	readAccess    sync.Mutex
	writeAccess   sync.Mutex
	readRemaining int
}

func (r *tlsObfsRecords) appendRecords(out []byte, p []byte) []byte {
	for len(p) > 0 {
		payloadLen := min(len(p), tlsObfsRecordPayloadLen)
		out = append(out, 0x17, 0x03, 0x03)
		out = binary.BigEndian.AppendUint16(out, uint16(payloadLen))
		out = append(out, p[:payloadLen]...)
		p = p[payloadLen:]
	}
	return out
}

func (r *tlsObfsRecords) readRecord(p []byte, headerLen int) (int, error) {
	if r.readRemaining > 0 {
		n, err := io.ReadFull(r.Conn, p[:min(r.readRemaining, len(p))])
		r.readRemaining -= n
		return n, err
	}
	_, err := io.CopyN(io.Discard, r.Conn, int64(headerLen-2))
	if err != nil {
		return 0, err
	}
	var lengthBytes [2]byte
	_, err = io.ReadFull(r.Conn, lengthBytes[:])
	if err != nil {
		return 0, err
	}
	payloadLen := int(binary.BigEndian.Uint16(lengthBytes[:]))
	if payloadLen == 0 {
		return 0, nil
	}
	if payloadLen > len(p) {
		n, err := io.ReadFull(r.Conn, p)
		r.readRemaining = payloadLen - n
		return n, err
	}
	return io.ReadFull(r.Conn, p[:payloadLen])
}

type tlsObfsClientConn struct {
	tlsObfsRecords
	config              ObfsConfig
	clientHelloSent     bool
	serverHelloReceived bool
}

func (c *tlsObfsClientConn) Read(p []byte) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	c.readAccess.Lock()
	defer c.readAccess.Unlock()
	if !c.serverHelloReceived {
		c.serverHelloReceived = true
		return c.readRecord(p, tlsObfsServerHelloPayloadOffset)
	}
	return c.readRecord(p, tlsObfsRecordHeaderLen)
}

func (c *tlsObfsClientConn) Write(p []byte) (int, error) {
	c.writeAccess.Lock()
	defer c.writeAccess.Unlock()
	if c.clientHelloSent {
		if len(p) == 0 {
			return 0, nil
		}
		_, err := c.Conn.Write(c.appendRecords(nil, p))
		if err != nil {
			return 0, E.Cause(err, "write tls obfs payload")
		}
		return len(p), nil
	}
	c.clientHelloSent = true
	host := c.config.Host
	if host == "" {
		host = DefaultTLSObfsHost
	}
	payload := p
	if len(payload) > tlsObfsClientHelloPayloadLen {
		payload = p[:tlsObfsClientHelloPayloadLen]
	}
	var random [28]byte
	common.Must1(rand.Read(random[:]))
	var sessionID [32]byte
	common.Must1(rand.Read(sessionID[:]))
	// Surge 6.4.4 (10661): -[SGObfsHelperTLS encodeObfsData:] patches this ClientHello template with
	// the current time, fresh randomness, the first 0x400 payload bytes as a session ticket and the
	// obfs hostname as the server name, then appends the remaining payload as normal records.
	out := make([]byte, 0, tlsObfsClientHelloOverhead+len(host)+len(p)+tlsObfsRecordHeaderLen)
	out = append(out, 0x16, 0x03, 0x01)
	out = binary.BigEndian.AppendUint16(out, uint16(tlsObfsClientRecordOverhead+len(host)+len(payload)))
	out = append(out, 0x01, 0x00)
	out = binary.BigEndian.AppendUint16(out, uint16(tlsObfsClientHandshakeOverhead+len(host)+len(payload)))
	out = append(out, 0x03, 0x03)
	out = binary.BigEndian.AppendUint32(out, uint32(time.Now().Unix()))
	out = append(out, random[:]...)
	out = append(out, 0x20)
	out = append(out, sessionID[:]...)
	out = binary.BigEndian.AppendUint16(out, 0x0038)
	out = append(out,
		0xc0, 0x2c, 0xc0, 0x30, 0x00, 0x9f, 0xcc, 0xa9, 0xcc, 0xa8, 0xcc, 0xaa, 0xc0, 0x2b, 0xc0, 0x2f,
		0x00, 0x9e, 0xc0, 0x24, 0xc0, 0x28, 0x00, 0x6b, 0xc0, 0x23, 0xc0, 0x27, 0x00, 0x67, 0xc0, 0x0a,
		0xc0, 0x14, 0x00, 0x39, 0xc0, 0x09, 0xc0, 0x13, 0x00, 0x33, 0x00, 0x9d, 0x00, 0x9c, 0x00, 0x3d,
		0x00, 0x3c, 0x00, 0x35, 0x00, 0x2f, 0x00, 0xff,
	)
	out = append(out, 0x01, 0x00)
	out = binary.BigEndian.AppendUint16(out, uint16(tlsObfsClientExtensionsOverhead+len(host)+len(payload)))
	out = binary.BigEndian.AppendUint16(out, 0x0023)
	out = binary.BigEndian.AppendUint16(out, uint16(len(payload)))
	out = append(out, payload...)
	out = binary.BigEndian.AppendUint16(out, 0x0000)
	out = binary.BigEndian.AppendUint16(out, uint16(len(host)+5))
	out = binary.BigEndian.AppendUint16(out, uint16(len(host)+3))
	out = append(out, 0x00)
	out = binary.BigEndian.AppendUint16(out, uint16(len(host)))
	out = append(out, host...)
	out = append(out,
		0x00, 0x0b, 0x00, 0x04, 0x03, 0x01, 0x00, 0x02,
		0x00, 0x0a, 0x00, 0x0a, 0x00, 0x08, 0x00, 0x1d, 0x00, 0x17, 0x00, 0x19, 0x00, 0x18,
		0x00, 0x0d, 0x00, 0x20, 0x00, 0x1e, 0x06, 0x01, 0x06, 0x02, 0x06, 0x03, 0x05, 0x01,
		0x05, 0x02, 0x05, 0x03, 0x04, 0x01, 0x04, 0x02, 0x04, 0x03, 0x03, 0x01, 0x03, 0x02,
		0x03, 0x03, 0x02, 0x01, 0x02, 0x02, 0x02, 0x03,
		0x00, 0x16, 0x00, 0x00,
		0x00, 0x17, 0x00, 0x00,
	)
	out = c.appendRecords(out, p[len(payload):])
	_, err := c.Conn.Write(out)
	if err != nil {
		return 0, E.Cause(err, "write tls obfs client hello")
	}
	return len(p), nil
}

type tlsObfsServerConn struct {
	tlsObfsRecords
	clientHelloReceived bool
	sessionNameSkipped  bool
	serverHelloSent     bool
}

func (c *tlsObfsServerConn) Read(p []byte) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	c.readAccess.Lock()
	defer c.readAccess.Unlock()
	if !c.clientHelloReceived {
		c.clientHelloReceived = true
		return c.readRecord(p, tlsObfsClientHelloPayloadOffset)
	}
	if !c.sessionNameSkipped && c.readRemaining == 0 {
		c.sessionNameSkipped = true
		// Surge 6.4.4 (10661): -[SGObfsHelperTLS encodeObfsData:] puts the 9 byte server name
		// extension header, the hostname and a fixed 0x42 byte extension trailer behind the session
		// ticket that carries the first payload.
		_, err := io.CopyN(io.Discard, c.Conn, tlsObfsClientHelloNameHeaderLen-2)
		if err != nil {
			return 0, err
		}
		var lengthBytes [2]byte
		_, err = io.ReadFull(c.Conn, lengthBytes[:])
		if err != nil {
			return 0, err
		}
		_, err = io.CopyN(io.Discard, c.Conn, int64(binary.BigEndian.Uint16(lengthBytes[:]))+tlsObfsClientHelloTrailerLen)
		if err != nil {
			return 0, err
		}
	}
	return c.readRecord(p, tlsObfsRecordHeaderLen)
}

func (c *tlsObfsServerConn) Write(p []byte) (int, error) {
	c.writeAccess.Lock()
	defer c.writeAccess.Unlock()
	if c.serverHelloSent {
		if len(p) == 0 {
			return 0, nil
		}
		_, err := c.Conn.Write(c.appendRecords(nil, p))
		if err != nil {
			return 0, E.Cause(err, "write tls obfs payload")
		}
		return len(p), nil
	}
	c.serverHelloSent = true
	payload := p
	if len(payload) > tlsObfsRecordPayloadLen {
		payload = p[:tlsObfsRecordPayloadLen]
	}
	var random [28]byte
	common.Must1(rand.Read(random[:]))
	var sessionID [32]byte
	common.Must1(rand.Read(sessionID[:]))
	// Surge 6.4.4 (10661): -[SGObfsHelperTLS decodeObfsData:outData:] never parses this response, it
	// only requires the first payload length at 0x69 and the payload itself at 0x6b.
	out := make([]byte, 0, tlsObfsServerHelloPayloadOffset+len(p)+tlsObfsRecordHeaderLen)
	out = append(out, 0x16, 0x03, 0x01)
	out = binary.BigEndian.AppendUint16(out, 91)
	out = append(out, 0x02, 0x00, 0x00, 0x57, 0x03, 0x03)
	out = binary.BigEndian.AppendUint32(out, uint32(time.Now().Unix()))
	out = append(out, random[:]...)
	out = append(out, 0x20)
	out = append(out, sessionID[:]...)
	out = append(out,
		0xcc, 0xa8, 0x00,
		0x00, 0x00,
		0xff, 0x01, 0x00, 0x01, 0x00,
		0x00, 0x17, 0x00, 0x00,
		0x00, 0x0b, 0x00, 0x02, 0x01, 0x00,
		0x14, 0x03, 0x03, 0x00, 0x01, 0x01,
		0x16, 0x03, 0x03,
	)
	out = binary.BigEndian.AppendUint16(out, uint16(len(payload)))
	out = append(out, payload...)
	out = c.appendRecords(out, p[len(payload):])
	_, err := c.Conn.Write(out)
	if err != nil {
		return 0, E.Cause(err, "write tls obfs server hello")
	}
	return len(p), nil
}
