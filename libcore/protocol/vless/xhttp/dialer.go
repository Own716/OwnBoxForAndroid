package xhttp

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptrace"
	"sync"

	"libcore/protocol/vless/internal/xray"
	"libcore/protocol/vless/internal/xray/signal/done"
)

// interface to abstract between use of browser dialer, vs net/http
type DialerClient interface {
	IsClosed() bool

	// ctx, url, body, uploadOnly
	OpenStream(context.Context, string, io.Reader, bool) (io.ReadCloser, net.Addr, net.Addr, error)

	// ctx, url, body, contentLength
	PostPacket(context.Context, string, io.Reader, int64) error
}

// implements xhttp.DialerClient in terms of direct network connections
type DefaultDialerClient struct {
	options     *V2RayXHTTPBaseOptions
	client      *http.Client
	closed      bool
	httpVersion string
	// pool of net.Conn, created using dialUploadConn
	uploadRawPool  *sync.Pool
	dialUploadConn func(ctxInner context.Context) (net.Conn, error)
}

func (c *DefaultDialerClient) IsClosed() bool {
	return c.closed
}

func (c *DefaultDialerClient) OpenStream(ctx context.Context, url string, body io.Reader, uploadOnly bool) (wrc io.ReadCloser, remoteAddr, localAddr net.Addr, err error) {
	// this is done when the TCP/UDP connection to the server was established,
	// and we can unblock the Dial function and print correct net addresses in
	// logs
	gotConn := done.New()
	ctxTrace := httptrace.WithClientTrace(ctx, &httptrace.ClientTrace{
		GotConn: func(connInfo httptrace.GotConnInfo) {
			remoteAddr = connInfo.Conn.RemoteAddr()
			localAddr = connInfo.Conn.LocalAddr()
			gotConn.Close()
		},
	})
	method := "GET" // stream-down
	if body != nil {
		method = "POST" // stream-up/one
	}
	req, _ := http.NewRequestWithContext(context.WithoutCancel(ctxTrace), method, url, body)
	req.Header = c.options.GetRequestHeader(url)
	if req.Header.Get("X-Accel-Buffering") == "" {
		req.Header.Set("X-Accel-Buffering", "no")
	}
	if req.Header.Get("Cache-Control") == "" {
		req.Header.Set("Cache-Control", "no-store, no-cache, must-revalidate")
	}
	if method == "GET" {
		if !c.options.NoSSEHeader && req.Header.Get("Accept") == "" {
			req.Header.Set("Accept", "text/event-stream")
		}
	} else if method == "POST" {
		if req.Header.Get("Content-Type") == "" {
			if !c.options.NoGRPCHeader {
				req.Header.Set("Content-Type", "application/grpc")
			} else {
				req.Header.Set("Content-Type", "application/octet-stream")
			}
		}
	}
	waitReader := &WaitReadCloser{Wait: make(chan struct{})}
	wrc = waitReader

	var doErr error
	var doErrMu sync.Mutex
	setDoErr := func(e error) {
		doErrMu.Lock()
		if doErr == nil {
			doErr = e
		}
		doErrMu.Unlock()
	}

	go func() {
		resp, dErr := c.client.Do(req)
		if dErr != nil {
			if !uploadOnly { // stream-down is enough
				c.closed = true
			}
			setDoErr(dErr)
			waitReader.SetErr(dErr)
			gotConn.Close()
			if closer, ok := body.(io.Closer); ok {
				closer.Close()
			}
			waitReader.Close()
			return
		}
		if resp.StatusCode != 200 || uploadOnly { // stream-up
			if resp.StatusCode != 200 {
				c.closed = true
				statusErr := fmt.Errorf("bad status code: %s", resp.Status)
				setDoErr(statusErr)
				waitReader.SetErr(statusErr)
				if closer, ok := body.(io.Closer); ok {
					closer.Close()
				}
			}
			io.Copy(io.Discard, io.LimitReader(resp.Body, 32*1024))
			resp.Body.Close() // if it is called immediately, the upload will be interrupted also
			waitReader.Close()
			return
		}
		waitReader.Set(resp.Body)
	}()

	if body == nil {
		select {
		case <-waitReader.Wait:
		case <-ctx.Done():
			c.closed = true
			waitReader.SetErr(ctx.Err())
			waitReader.Close()
			return nil, nil, nil, ctx.Err()
		}
	} else {
		select {
		case <-gotConn.Wait():
		case <-waitReader.Wait:
		case <-ctx.Done():
			c.closed = true
			waitReader.SetErr(ctx.Err())
			waitReader.Close()
			if closer, ok := body.(io.Closer); ok {
				closer.Close()
			}
			return nil, nil, nil, ctx.Err()
		}
	}

	doErrMu.Lock()
	err = doErr
	doErrMu.Unlock()
	if err != nil {
		return nil, nil, nil, err
	}
	return
}

func (c *DefaultDialerClient) PostPacket(ctx context.Context, url string, body io.Reader, contentLength int64) error {
	req, err := http.NewRequestWithContext(ctx, "POST", url, body)
	if err != nil {
		return err
	}
	req.ContentLength = contentLength
	req.Header = c.options.GetRequestHeader(url)
	if req.Header.Get("X-Accel-Buffering") == "" {
		req.Header.Set("X-Accel-Buffering", "no")
	}
	if req.Header.Get("Cache-Control") == "" {
		req.Header.Set("Cache-Control", "no-store, no-cache, must-revalidate")
	}
	if req.Header.Get("Content-Type") == "" {
		if !c.options.NoGRPCHeader {
			req.Header.Set("Content-Type", "application/grpc")
		} else {
			req.Header.Set("Content-Type", "application/octet-stream")
		}
	}
	if c.httpVersion != "1.1" {
		resp, err := c.client.Do(req)
		if err != nil {
			c.closed = true
			return err
		}
		_, copyErr := io.Copy(io.Discard, io.LimitReader(resp.Body, 32*1024))
		closeErr := resp.Body.Close()
		if resp.StatusCode != 200 {
			c.closed = true
			if copyErr != nil {
				return copyErr
			}
			if closeErr != nil {
				return closeErr
			}
			return fmt.Errorf("bad status code: %s", resp.Status)
		}
		if copyErr != nil {
			return copyErr
		}
		if closeErr != nil {
			return closeErr
		}
	} else {
		// stringify the entire HTTP/1.1 request so it can be
		// safely retried. if instead req.Write is called multiple
		// times, the body is already drained after the first
		// request
		requestBuff := new(bytes.Buffer)
		common.Must(req.Write(requestBuff))
		var uploadConn any
		var h1UploadConn *H1Conn
		for {
			uploadConn = c.uploadRawPool.Get()
			newConnection := uploadConn == nil
			if newConnection {
				newConn, err := c.dialUploadConn(context.WithoutCancel(ctx))
				if err != nil {
					return err
				}
				h1UploadConn = NewH1Conn(newConn)
				uploadConn = h1UploadConn
			} else {
				h1UploadConn = uploadConn.(*H1Conn)
			}
			_, err := h1UploadConn.Write(requestBuff.Bytes())
			if err == nil {
				h1UploadConn.UnreadedResponsesCount++
				break
			}
			h1UploadConn.Close()
			if newConnection {
				return err
			}
		}
		for h1UploadConn.UnreadedResponsesCount > 0 {
			resp, err := http.ReadResponse(h1UploadConn.RespBufReader, req)
			if err != nil {
				c.closed = true
				h1UploadConn.Close()
				return fmt.Errorf("error while reading response: %s", err.Error())
			}
			_, copyErr := io.Copy(io.Discard, io.LimitReader(resp.Body, 32*1024))
			closeErr := resp.Body.Close()
			h1UploadConn.UnreadedResponsesCount--
			if resp.StatusCode != 200 {
				c.closed = true
				h1UploadConn.Close()
				if copyErr != nil {
					return copyErr
				}
				if closeErr != nil {
					return closeErr
				}
				return fmt.Errorf("got non-200 error response code: %d", resp.StatusCode)
			}
			if copyErr != nil {
				h1UploadConn.Close()
				return copyErr
			}
			if closeErr != nil {
				h1UploadConn.Close()
				return closeErr
			}
		}
		c.uploadRawPool.Put(uploadConn)
	}

	return nil
}

type WaitReadCloser struct {
	Wait   chan struct{}
	io.ReadCloser
	err    error
	mu     sync.Mutex
	once   sync.Once
	closed bool
}

func (w *WaitReadCloser) notify() {
	w.once.Do(func() {
		close(w.Wait)
	})
}

func (w *WaitReadCloser) Set(rc io.ReadCloser) {
	w.mu.Lock()
	if w.closed || w.ReadCloser != nil {
		w.mu.Unlock()
		rc.Close()
		return
	}
	w.ReadCloser = rc
	w.mu.Unlock()
	w.notify()
}

func (w *WaitReadCloser) SetErr(err error) {
	w.mu.Lock()
	if w.err == nil {
		w.err = err
	}
	w.mu.Unlock()
	w.notify()
}

func (w *WaitReadCloser) Read(b []byte) (int, error) {
	w.mu.Lock()
	rc := w.ReadCloser
	err := w.err
	w.mu.Unlock()

	if rc == nil {
		if err != nil {
			return 0, err
		}
		<-w.Wait
		w.mu.Lock()
		rc = w.ReadCloser
		err = w.err
		w.mu.Unlock()
		if rc == nil {
			if err != nil {
				return 0, err
			}
			return 0, io.ErrClosedPipe
		}
	}
	return rc.Read(b)
}

func (w *WaitReadCloser) Close() error {
	w.mu.Lock()
	if w.closed {
		w.mu.Unlock()
		return nil
	}
	w.closed = true
	rc := w.ReadCloser
	w.ReadCloser = nil
	w.mu.Unlock()

	w.notify()
	if rc != nil {
		return rc.Close()
	}

	return nil
}
