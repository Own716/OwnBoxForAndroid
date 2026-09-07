package snellv4

import (
	"context"
	"errors"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"time"

	snell "libcore/protocol/snell/internal/singsnell"
	"libcore/protocol/snell/internal/singsnell/internal/reuse"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/buf"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

func (c *Client) DialContext(ctx context.Context, destination M.Socksaddr) (net.Conn, error) {
	if c.reuse {
		session, err := c.reuseSession(ctx)
		if err != nil {
			return nil, err
		}
		if snell.KeepSessionFromContext(ctx) {
			session.keepOnce.Store(true)
		}
		conn, err := session.DialConn(destination)
		if err != nil {
			session.Close()
			return nil, err
		}
		return conn, nil
	}
	if c.pool.IsClosed() {
		return nil, net.ErrClosed
	}
	if c.dialer == nil {
		return nil, E.New("snell: missing dialer")
	}
	conn, err := c.dialer.DialContext(ctx, N.NetworkTCP, c.server)
	if err != nil {
		return nil, err
	}
	// Surge 6.4.4 (10661): -[SNConnectorV4 targetHandshakeData] appends the connector early data to
	// the handshake buffer, and -[SNConnectorV4 firstDataPacket] encrypts and obfuscates the result
	// in one piece, so the first record, and with obfs the first HTTP request body, carries both.
	return c.DialEarlyConn(conn, destination), nil
}

func (c *Client) reuseSession(ctx context.Context) (*reuseSession, error) {
	session, found, closed := c.pool.Take()
	if closed {
		return nil, net.ErrClosed
	}
	if c.dialer == nil {
		return nil, E.New("snell: missing dialer")
	}
	if found {
		return session, nil
	}
	conn, err := c.dialer.DialContext(ctx, N.NetworkTCP, c.server)
	if err != nil {
		return nil, err
	}
	if c.pool.IsClosed() {
		conn.Close()
		return nil, net.ErrClosed
	}
	session = c.newReuseSession(conn)
	session.state.Store(uint32(reuse.StateActive))
	return session, nil
}

func (c *Client) Reset() {
	if c.reuse {
		c.pool.Reset()
	}
}

func (c *Client) SetKeepIdleConnections(keep bool) {
	c.closeIdle.Store(!keep)
	if !keep {
		c.CloseIdleConnections()
	}
}

func (c *Client) CloseIdleConnections() {
	if c.reuse {
		c.pool.Reset()
	}
}

func (c *Client) Close() error {
	return c.pool.Close()
}

type reuseSession struct {
	net.Conn
	client *Client

	state    atomic.Uint32
	keepOnce atomic.Bool
	reader   *reader
	writer   *writer
}

func (c *Client) newReuseSession(conn net.Conn) *reuseSession {
	return &reuseSession{Conn: c.obfs.ClientConn(conn), client: c}
}

func (s *reuseSession) ReuseState() *atomic.Uint32 {
	return &s.state
}

func (s *reuseSession) DialConn(destination M.Socksaddr) (net.Conn, error) {
	state := reuse.State(s.state.Load())
	if state == reuse.StateClosed {
		return nil, net.ErrClosed
	}
	if state != reuse.StateActive {
		return nil, E.New("snell: reuse session is busy")
	}

	return &reuseConn{Conn: s.Conn, session: s, destination: destination}, nil
}

func (s *reuseSession) closeIdle() bool {
	return s.client.closeIdle.Load() && !s.keepOnce.Swap(false)
}

func (s *reuseSession) Release(reusable bool) {
	if !reusable || s.closeIdle() {
		s.Close()
		return
	}
	if !s.state.CompareAndSwap(uint32(reuse.StateActive), uint32(reuse.StateReady)) {
		if reuse.State(s.state.Load()) != reuse.StateClosed {
			s.Close()
		}
		return
	}
	s.client.pool.MoveToPool(s, reuse.StateReady, false)
}

func (s *reuseSession) Close() error {
	if reuse.State(s.state.Swap(uint32(reuse.StateClosed))) == reuse.StateClosed {
		return nil
	}
	return s.Conn.Close()
}

func (s *reuseSession) startDrain() {
	if s.closeIdle() {
		s.Close()
		return
	}
	if !s.state.CompareAndSwap(uint32(reuse.StateActive), uint32(reuse.StateWaiting)) {
		if reuse.State(s.state.Load()) != reuse.StateClosed {
			s.Close()
		}
		return
	}
	if !s.client.pool.MoveToPool(s, reuse.StateWaiting, true) {
		return
	}
	go s.drain()
}

func (s *reuseSession) drain() {
	defer s.client.pool.DrainDone()
	var discarded int
	for {
		record, err := s.reader.NextRecord()
		if errors.Is(err, io.EOF) {
			s.state.CompareAndSwap(uint32(reuse.StateWaiting), uint32(reuse.StateReady))
			return
		}
		if err != nil {
			s.Close()
			return
		}
		// Surge 6.7.0 (11520): SNConnectorV4::socket:didReadData: discards data
		// received while waiting for server EOF and closes after 0x80001 bytes.
		discarded += record.Len()
		record.Release()
		if discarded >= reuse.WaitingDiscardLimit {
			s.Close()
			return
		}
	}
}

type reuseConn struct {
	net.Conn
	session     *reuseSession
	destination M.Socksaddr

	access          sync.Mutex
	writer          *writer
	closeWriteOnce  sync.Once
	closeWriteErr   error
	closeOnce       sync.Once
	closeErr        error
	replyRead       atomic.Bool
	readWaitOptions N.ReadWaitOptions
	closed          atomic.Bool
	readActionCount atomic.Int32
	readClosed      atomic.Bool
}

func (c *reuseConn) readResponse() error {
	if c.replyRead.Load() {
		return nil
	}
	if c.session.reader == nil {
		c.session.reader = &reader{upstream: c.session.Conn, psk: c.session.client.psk}
		c.session.reader.InitializeReadWaiter(c.readWaitOptions)
	}
	record, err := c.session.reader.ReadRecord()
	if err != nil {
		return E.Cause(err, "read reply")
	}
	cached, err := reuse.ParseReply(record)
	if err != nil {
		return err
	}
	if cached != nil {
		c.session.reader.SetCache(cached)
	}
	c.replyRead.Store(true)
	return nil
}

func (c *reuseConn) Read(p []byte) (int, error) {
	if c.closed.Load() {
		return 0, net.ErrClosed
	}
	c.readActionCount.Add(1)
	defer c.readActionCount.Add(-1)
	if c.closed.Load() {
		return 0, net.ErrClosed
	}
	err := c.readResponse()
	if err != nil {
		return 0, err
	}
	var discarded int
	for {
		n, err := c.session.reader.Read(p)
		if c.closed.Load() {
			discarded += n
			if errors.Is(err, io.EOF) {
				c.readClosed.Store(true)
				c.session.state.CompareAndSwap(uint32(reuse.StateWaiting), uint32(reuse.StateReady))
				return 0, err
			}
			if err != nil {
				c.session.Close()
				return 0, err
			}
			if discarded >= reuse.WaitingDiscardLimit {
				c.session.Close()
				return 0, E.New("snell: read too much data after client EOF")
			}
			continue
		}
		if errors.Is(err, io.EOF) {
			c.readClosed.Store(true)
		}
		return n, err
	}
}

func (c *reuseConn) ReadBuffer(buffer *buf.Buffer) error {
	if c.closed.Load() {
		return net.ErrClosed
	}
	c.readActionCount.Add(1)
	defer c.readActionCount.Add(-1)
	if c.closed.Load() {
		return net.ErrClosed
	}
	err := c.readResponse()
	if err != nil {
		return err
	}
	var discarded int
	for {
		bufferLen := buffer.Len()
		err = c.session.reader.ReadBuffer(buffer)
		if c.closed.Load() {
			discarded += buffer.Len() - bufferLen
			buffer.Truncate(bufferLen)
			if errors.Is(err, io.EOF) {
				c.readClosed.Store(true)
				c.session.state.CompareAndSwap(uint32(reuse.StateWaiting), uint32(reuse.StateReady))
				return err
			}
			if err != nil {
				c.session.Close()
				return err
			}
			if discarded >= reuse.WaitingDiscardLimit {
				c.session.Close()
				return E.New("snell: read too much data after client EOF")
			}
			continue
		}
		if errors.Is(err, io.EOF) {
			c.readClosed.Store(true)
		}
		return err
	}
}

func (c *reuseConn) writeRequest(payload []byte) error {
	requestPayload := snell.Request{Command: snell.CommandConnectV2, ClientID: c.session.client.userKey, Destination: c.destination}
	request := buf.NewSize(requestPayload.Len() + len(payload))
	err := requestPayload.Write(request)
	if err != nil {
		request.Release()
		return err
	}
	if len(payload) > 0 {
		common.Must1(request.Write(payload))
	}
	defer request.Release()

	if c.session.writer == nil {
		c.session.writer = &writer{
			upstream: c.session.Conn,
			psk:      c.session.client.psk,
		}
	}
	_, err = c.session.writer.Write(request.Bytes())
	if err != nil {
		c.session.Release(false)
		return E.Cause(err, "write request")
	}
	c.writer = c.session.writer
	return nil
}

func (c *reuseConn) writeRequestBuffer(buffer *buf.Buffer) error {
	requestPayload := snell.Request{Command: snell.CommandConnectV2, ClientID: c.session.client.userKey, Destination: c.destination}
	request := buf.With(buffer.ExtendHeader(requestPayload.Len()))
	err := requestPayload.Write(request)
	if err != nil {
		buffer.Release()
		return err
	}
	if c.session.writer == nil {
		c.session.writer = &writer{
			upstream: c.session.Conn,
			psk:      c.session.client.psk,
		}
	}
	err = c.session.writer.WriteBuffer(buffer)
	if err != nil {
		c.session.Release(false)
		return E.Cause(err, "write request")
	}
	c.writer = c.session.writer
	return nil
}

func (c *reuseConn) Write(p []byte) (int, error) {
	if c.writer != nil {
		return c.writer.Write(p)
	}
	c.access.Lock()
	if c.writer != nil {
		c.access.Unlock()
		return c.writer.Write(p)
	}
	defer c.access.Unlock()
	err := c.writeRequest(p)
	if err != nil {
		return 0, err
	}
	return len(p), nil
}

func (c *reuseConn) WriteBuffer(buffer *buf.Buffer) error {
	if c.writer != nil {
		return c.writer.WriteBuffer(buffer)
	}
	c.access.Lock()
	if c.writer != nil {
		c.access.Unlock()
		return c.writer.WriteBuffer(buffer)
	}
	defer c.access.Unlock()
	return c.writeRequestBuffer(buffer)
}

func (c *reuseConn) CreateVectorisedWriter() (N.VectorisedWriter, bool) {
	upstreamWriter, created := bufio.CreateVectorisedWriter(c.session.Conn)
	if !created {
		return nil, false
	}
	return &reuseVectorisedWriter{conn: c, upstream: upstreamWriter}, true
}

type reuseVectorisedWriter struct {
	conn     *reuseConn
	upstream N.VectorisedWriter
}

func (w *reuseVectorisedWriter) WriteVectorised(buffers []*buf.Buffer) error {
	conn := w.conn
	if conn.writer != nil {
		return conn.writer.CreateVectorisedWriterFor(w.upstream).WriteVectorised(buffers)
	}
	conn.access.Lock()
	defer conn.access.Unlock()
	if conn.writer != nil {
		return conn.writer.CreateVectorisedWriterFor(w.upstream).WriteVectorised(buffers)
	}
	for index, buffer := range buffers {
		if buffer.IsEmpty() {
			buffer.Release()
			continue
		}
		err := conn.writeRequestBuffer(buffer)
		if err != nil {
			buf.ReleaseMulti(buffers[index+1:])
			return err
		}
		if index+1 < len(buffers) {
			return conn.writer.CreateVectorisedWriterFor(w.upstream).WriteVectorised(buffers[index+1:])
		}
		return nil
	}
	return nil
}

func (c *reuseConn) CloseWrite() error {
	c.closeWriteOnce.Do(func() {
		c.access.Lock()
		defer c.access.Unlock()
		if c.writer == nil {
			c.closeWriteErr = c.writeRequest(nil)
			if c.closeWriteErr != nil {
				return
			}
		}
		c.closeWriteErr = c.writer.WriteZeroChunk()
	})
	return c.closeWriteErr
}

func (c *reuseConn) Close() error {
	c.closeOnce.Do(func() {
		c.closed.Store(true)
		if !c.replyRead.Load() {
			c.session.Release(false)
			return
		}
		c.closeErr = c.CloseWrite()
		if c.closeErr != nil {
			c.session.Release(false)
			return
		}
		c.session.Conn.SetReadDeadline(time.Time{})
		c.session.Conn.SetWriteDeadline(time.Time{})
		if c.readClosed.Load() {
			c.session.Release(true)
			return
		}
		if c.session.closeIdle() {
			c.session.Close()
			return
		}
		// Surge 6.7.0 (11520): SNConnectorV4::readServerEOFIfNotInReadState: starts waiting-state EOF
		// reads when _socketReadActionCounter is 0.
		if c.readActionCount.Load() > 0 {
			if !c.session.state.CompareAndSwap(uint32(reuse.StateActive), uint32(reuse.StateWaiting)) {
				if reuse.State(c.session.state.Load()) != reuse.StateClosed {
					c.session.Close()
				}
				return
			}
			poolState := reuse.StateWaiting
			if c.readClosed.Load() {
				c.session.state.CompareAndSwap(uint32(reuse.StateWaiting), uint32(reuse.StateReady))
				poolState = reuse.StateReady
			}
			if !c.session.client.pool.MoveToPool(c.session, poolState, false) {
				return
			}
			return
		}
		c.session.startDrain()
	})
	return c.closeErr
}

func (c *reuseConn) CreateReadWaiter() (N.ReadWaiter, bool) {
	return &reuseReadWaiter{conn: c}, true
}

type reuseReadWaiter struct {
	conn *reuseConn
}

func (w *reuseReadWaiter) InitializeReadWaiter(options N.ReadWaitOptions) (needCopy bool) {
	w.conn.readWaitOptions = options
	if w.conn.session.reader != nil {
		w.conn.session.reader.InitializeReadWaiter(options)
	}
	return false
}

func (w *reuseReadWaiter) WaitReadBuffer() (*buf.Buffer, error) {
	if w.conn.closed.Load() {
		return nil, net.ErrClosed
	}
	w.conn.readActionCount.Add(1)
	defer w.conn.readActionCount.Add(-1)
	if w.conn.closed.Load() {
		return nil, net.ErrClosed
	}
	err := w.conn.readResponse()
	if err != nil {
		return nil, err
	}
	var discarded int
	for {
		buffer, err := w.conn.session.reader.WaitReadBuffer()
		if w.conn.closed.Load() {
			if buffer != nil {
				discarded += buffer.Len()
				buffer.Release()
			}
			if errors.Is(err, io.EOF) {
				w.conn.readClosed.Store(true)
				w.conn.session.state.CompareAndSwap(uint32(reuse.StateWaiting), uint32(reuse.StateReady))
				return nil, err
			}
			if err != nil {
				w.conn.session.Close()
				return nil, err
			}
			if discarded >= reuse.WaitingDiscardLimit {
				w.conn.session.Close()
				return nil, E.New("snell: read too much data after client EOF")
			}
			continue
		}
		if errors.Is(err, io.EOF) {
			w.conn.readClosed.Store(true)
		}
		return buffer, err
	}
}

func (c *reuseConn) FrontHeadroom() int {
	if c.writer != nil {
		return c.writer.FrontHeadroom()
	}
	requestPayload := snell.Request{Command: snell.CommandConnectV2, ClientID: c.session.client.userKey, Destination: c.destination}
	return requestPayload.Len() + snell.SaltLen + snell.HeaderCipherLen + maxInitialPaddingLen
}

func (c *reuseConn) RearHeadroom() int {
	return snell.AEADTagLen
}

func (c *reuseConn) WriterMTU() int {
	return maxPayload
}

func (c *reuseConn) NeedHandshakeForRead() bool {
	return !c.replyRead.Load()
}

func (c *reuseConn) NeedHandshakeForWrite() bool {
	return c.writer == nil
}

func (c *reuseConn) NeedAdditionalReadDeadline() bool {
	return !c.replyRead.Load()
}

func (c *reuseConn) Upstream() any {
	return c.session.Conn
}

func (c *reuseConn) RemoteAddr() net.Addr {
	return c.destination.TCPAddr()
}

var (
	_ N.ExtendedConn           = (*reuseConn)(nil)
	_ N.ReadWaitCreator        = (*reuseConn)(nil)
	_ N.VectorisedWriteCreator = (*reuseConn)(nil)
	_ N.EarlyReader            = (*reuseConn)(nil)
	_ N.EarlyWriter            = (*reuseConn)(nil)
	_ N.WriteCloser            = (*reuseConn)(nil)
	_ N.VectorisedWriter       = (*reuseVectorisedWriter)(nil)
	_ N.ReadWaiter             = (*reuseReadWaiter)(nil)
)
