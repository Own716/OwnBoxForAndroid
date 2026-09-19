package xhttp

import (
	"bufio"
	"io"
	"net"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"libcore/protocol/vless/internal/xray/signal/done"
)

type splitConn struct {
	writer     io.WriteCloser
	reader     io.ReadCloser
	remoteAddr net.Addr
	localAddr  net.Addr
	onClose    func()
	closeOnce  sync.Once
	closed     atomic.Bool
}

func (c *splitConn) Write(b []byte) (int, error) {
	if c.closed.Load() {
		return 0, io.ErrClosedPipe
	}
	return c.writer.Write(b)
}

func (c *splitConn) Read(b []byte) (int, error) {
	return c.reader.Read(b)
}

func (c *splitConn) Close() error {
	var err error
	c.closeOnce.Do(func() {
		c.closed.Store(true)
		if c.onClose != nil {
			c.onClose()
		}
		var err1, err2 error
		if c.writer != nil {
			err1 = c.writer.Close()
		}
		if c.reader != nil {
			err2 = c.reader.Close()
		}
		if err1 != nil {
			err = err1
		} else {
			err = err2
		}
	})
	return err
}

func (c *splitConn) IsClosed() bool {
	return c.closed.Load()
}

func (c *splitConn) LocalAddr() net.Addr {
	return c.localAddr
}

func (c *splitConn) RemoteAddr() net.Addr {
	return c.remoteAddr
}

func (c *splitConn) SetDeadline(t time.Time) error {
	return nil
}

func (c *splitConn) SetReadDeadline(t time.Time) error {
	return nil
}

func (c *splitConn) SetWriteDeadline(t time.Time) error {
	return nil
}

type H1Conn struct {
	UnreadedResponsesCount int
	RespBufReader          *bufio.Reader
	net.Conn
	closed atomic.Bool
}

func NewH1Conn(conn net.Conn) *H1Conn {
	return &H1Conn{
		RespBufReader: bufio.NewReader(conn),
		Conn:          conn,
	}
}

func (c *H1Conn) Close() error {
	if c.closed.CompareAndSwap(false, true) {
		return c.Conn.Close()
	}
	return nil
}

func (c *H1Conn) IsClosed() bool {
	return c.closed.Load()
}

type httpServerConn struct {
	sync.Mutex
	*done.Instance
	io.Reader // no need to Close request.Body
	http.ResponseWriter
}

func (c *httpServerConn) Write(b []byte) (int, error) {
	c.Lock()
	defer c.Unlock()
	if c.Done() {
		return 0, io.ErrClosedPipe
	}
	n, err := c.ResponseWriter.Write(b)
	if err == nil {
		c.ResponseWriter.(http.Flusher).Flush()
	}
	return n, err
}

func (c *httpServerConn) Close() error {
	c.Lock()
	defer c.Unlock()
	return c.Instance.Close()
}
