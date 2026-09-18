package xhttp

import (
	"context"
	gotls "crypto/tls"
	"fmt"
	"io"
	"net/http"
	"net/http/httptrace"
	"net/url"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/http3"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/log"
	qtls "github.com/sagernet/sing-quic"
	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	sHTTP "github.com/sagernet/sing/protocol/http"
	"github.com/sagernet/sing/service"
	"golang.org/x/net/http2"
	"libcore/protocol/vless/internal/xray/buf"
	"libcore/protocol/vless/internal/xray/net"
	"libcore/protocol/vless/internal/xray/pipe"
	"libcore/protocol/vless/internal/xray/signal/done"
	"libcore/protocol/vless/internal/xray/uuid"
)

type Client struct {
	ctx            context.Context
	cancel         context.CancelFunc
	options        *V2RayXHTTPOptions
	dest           M.Socksaddr
	rawURL         string
	rawURL2        string
	xmuxManager    *XmuxManager
	xmuxManager2   *XmuxManager
	getHTTPClient  func() (DialerClient, *XmuxClient)
	getHTTPClient2 func() (DialerClient, *XmuxClient)
}

func NewClient(ctx context.Context, dialer N.Dialer, serverAddr M.Socksaddr, options V2RayXHTTPOptions, tlsConfig tls.Config) (adapter.V2RayClientTransport, error) {
	configMode, err := NormalizeXHTTPMode(options.Mode)
	if err != nil {
		return nil, err
	}
	if options.Download != nil {
		options.Download.Mode, err = NormalizeXHTTPMode(options.Download.Mode)
		if err != nil {
			return nil, err
		}
		if configMode == "stream-one" {
			return nil, E.New(`download is not allowed when mode is "stream-one"`)
		}
	}
	mode := configMode
	dest := serverAddr
	if mode == "auto" {
		mode = "packet-up"
	}
	options.Mode = mode
	baseRequestURL, err := getBaseRequestURL(
		&options.V2RayXHTTPBaseOptions, dest, tlsConfig,
	)
	if err != nil {
		return nil, err
	}
	clientCtx, cancel := context.WithCancel(ctx)
	var xmuxOptions V2RayXHTTPXmuxOptions
	if options.Xmux != nil {
		xmuxOptions = *options.Xmux
		if err := xmuxOptions.Validate(); err != nil {
			cancel()
			return nil, err
		}
	}
	xmuxManager := NewXmuxManager(xmuxOptions, func() XmuxConn {
		return createHTTPClient(dest, dialer, &options.V2RayXHTTPBaseOptions, tlsConfig)
	})
	getHTTPClient := func() (DialerClient, *XmuxClient) {
		xmuxClient := xmuxManager.GetXmuxClient(clientCtx)
		return xmuxClient.XmuxConn.(DialerClient), xmuxClient
	}
	rawURL := baseRequestURL.String()
	rawURL2 := rawURL
	getHTTPClient2 := getHTTPClient
	var xmuxManager2 *XmuxManager
	if options.Download != nil {
		options2 := options.Download
		dialer2 := dialer
		if options2.Detour != "" {
			var ok bool
			dialer2, ok = service.FromContext[adapter.OutboundManager](ctx).Outbound(options2.Detour)
			if !ok {
				cancel()
				return nil, E.New("outbound detour not found: ", options2.Detour)
			}
		}
		dest2 := options2.ServerOptions.Build()
		var tlsConfig2 tls.Config
		if options2.TLS != nil {
			tlsConfig2, err = tls.NewClient(ctx, log.StdLogger(), options2.Server, common.PtrValueOrDefault(options2.TLS))
			if err != nil {
				cancel()
				return nil, err
			}
		}
		baseRequestURL2, err := getBaseRequestURL(&options2.V2RayXHTTPBaseOptions, dest2, tlsConfig2)
		if err != nil {
			cancel()
			return nil, err
		}
		rawURL2 = baseRequestURL2.String()
		var xmuxOptions2 V2RayXHTTPXmuxOptions
		if options2.Xmux != nil {
			xmuxOptions2 = *options2.Xmux
			if err := xmuxOptions2.Validate(); err != nil {
				cancel()
				return nil, err
			}
		}
		xmuxManager2 = NewXmuxManager(xmuxOptions2, func() XmuxConn {
			return createHTTPClient(dest2, dialer2, &options2.V2RayXHTTPBaseOptions, tlsConfig2)
		})
		getHTTPClient2 = func() (DialerClient, *XmuxClient) {
			xmuxClient2 := xmuxManager2.GetXmuxClient(clientCtx)
			return xmuxClient2.XmuxConn.(DialerClient), xmuxClient2
		}
	}
	return &Client{
		ctx:            clientCtx,
		cancel:         cancel,
		options:        &options,
		dest:           dest,
		rawURL:         rawURL,
		rawURL2:        rawURL2,
		xmuxManager:    xmuxManager,
		xmuxManager2:   xmuxManager2,
		getHTTPClient:  getHTTPClient,
		getHTTPClient2: getHTTPClient2,
	}, nil
}

func (c *Client) DialContext(ctx context.Context) (net.Conn, error) {
	options := c.options
	mode := c.options.Mode
	sessionIdUuid := uuid.New()
	sessionId := sessionIdUuid.String()
	httpClient, xmuxClient := c.getHTTPClient()
	var httpClient2 DialerClient
	var xmuxClient2 *XmuxClient
	if mode != "stream-one" {
		httpClient2, xmuxClient2 = c.getHTTPClient2()
	}
	if xmuxClient != nil {
		xmuxClient.OpenUsage.Add(1)
	}
	if xmuxClient2 != nil && xmuxClient2 != xmuxClient {
		xmuxClient2.OpenUsage.Add(1)
	}
	var closed atomic.Int32
	uploadBaseCtx := context.WithoutCancel(ctx)
	uploadCtx, cancelUpload := context.WithCancel(uploadBaseCtx)
	reader, writer := io.Pipe()
	conn := splitConn{
		writer: writer,
		onClose: func() {
			if closed.Add(1) > 1 {
				return
			}
			cancelUpload()
			if xmuxClient != nil {
				xmuxClient.OpenUsage.Add(-1)
			}
			if xmuxClient2 != nil && xmuxClient2 != xmuxClient {
				xmuxClient2.OpenUsage.Add(-1)
			}
		},
	}
	var err error
	if mode == "stream-one" {
		if xmuxClient != nil {
			xmuxClient.LeftRequests.Add(-1)
		}
		conn.reader, conn.remoteAddr, conn.localAddr, err = httpClient.OpenStream(ctx, c.rawURL, sessionId, reader, false)
		if err != nil {
			conn.Close()
			return nil, err
		}
		return &conn, nil
	} else { // stream-down
		if xmuxClient2 != nil {
			xmuxClient2.LeftRequests.Add(-1)
		}
		conn.reader, conn.remoteAddr, conn.localAddr, err = httpClient2.OpenStream(ctx, c.rawURL2, sessionId, nil, false)
		if err != nil { // browser dialer only
			conn.Close()
			return nil, err
		}
	}
	if mode == "stream-up" {
		if xmuxClient != nil {
			xmuxClient.LeftRequests.Add(-1)
		}
		_, _, _, err = httpClient.OpenStream(ctx, c.rawURL, sessionId, reader, true)
		if err != nil { // browser dialer only
			conn.Close()
			return nil, err
		}
		return &conn, nil
	}
	scMaxEachPostBytes := options.GetNormalizedScMaxEachPostBytes()
	scMinPostsIntervalMs := options.GetNormalizedScMinPostsIntervalMs()
	if scMaxEachPostBytes.From <= buf.Size {
		panic("`scMaxEachPostBytes` should be bigger than " + strconv.Itoa(buf.Size))
	}
	maxUploadSize := scMaxEachPostBytes.Rand()
	// WithSizeLimit(0) will still allow single bytes to pass, and a lot of
	// code relies on this behavior. Subtract 1 so that together with
	// uploadWriter wrapper, exact size limits can be enforced
	uploadPipeReader, uploadPipeWriter := pipe.New(pipe.WithSizeLimit(maxUploadSize - buf.Size))
	conn.writer = uploadWriter{
		uploadPipeWriter,
		maxUploadSize,
	}
	go func() {
		defer uploadPipeReader.Interrupt()
		var seq int64
		var lastWrite time.Time
		for {
			select {
			case <-uploadCtx.Done():
				return
			default:
			}
			wroteRequest := done.New()
			reqCtx := httptrace.WithClientTrace(uploadCtx, &httptrace.ClientTrace{
				WroteRequest: func(httptrace.WroteRequestInfo) {
					wroteRequest.Close()
				},
			})
			curSeq := seq
			seq += 1
			if scMinPostsIntervalMs.From > 0 {
				time.Sleep(time.Duration(scMinPostsIntervalMs.Rand())*time.Millisecond - time.Since(lastWrite))
			}
			// by offloading the uploads into a buffered pipe, multiple conn.Write
			// calls get automatically batched together into larger POST requests.
			// without batching, bandwidth is extremely limited.
			chunk, err := uploadPipeReader.ReadMultiBuffer()
			if err != nil {
				return
			}
			select {
			case <-uploadCtx.Done():
				return
			default:
			}
			lastWrite = time.Now()
			if xmuxClient != nil && (xmuxClient.LeftRequests.Add(-1) <= 0 ||
				(xmuxClient.UnreusableAt != time.Time{} && lastWrite.After(xmuxClient.UnreusableAt))) {
				httpClient, xmuxClient = c.getHTTPClient()
			}
			go func(chunk buf.MultiBuffer, currentSeq int64, baseCtx context.Context) {
				postCtx, cancelPost := context.WithCancel(baseCtx)
				defer cancelPost()
				defer wroteRequest.Close()
				err := httpClient.PostPacket(
					postCtx,
					c.rawURL,
					sessionId,
					currentSeq,
					&buf.MultiBufferContainer{MultiBuffer: chunk},
					int64(chunk.Len()),
				)
				if err != nil {
					uploadPipeReader.Interrupt()
				}
			}(chunk, curSeq, reqCtx)
			if _, ok := httpClient.(*DefaultDialerClient); ok {
				select {
				case <-wroteRequest.Wait():
				case <-uploadCtx.Done():
					return
				}
			}
		}
	}()
	return &conn, nil
}

func (c *Client) Close() error {
	if c.cancel != nil {
		c.cancel()
	}
	if c.xmuxManager != nil {
		_ = c.xmuxManager.Close()
	}
	if c.xmuxManager2 != nil {
		_ = c.xmuxManager2.Close()
	}
	return nil
}

func decideHTTPVersion(tlsConfig tls.Config) string {
	if isRealityConfig(tlsConfig) {
		return "2"
	}
	if tlsConfig == nil {
		return "1.1"
	}
	nextProtos := tlsConfig.NextProtos()

	if len(nextProtos) == 0 {
		tlsConfig.SetNextProtos([]string{http2.NextProtoTLS, "http/1.1"})
		nextProtos = tlsConfig.NextProtos()
	}

	for _, proto := range nextProtos {
		if proto == "h3" {
			return "3"
		}
	}
	hasH2 := false
	hasH1 := false
	for _, proto := range nextProtos {
		if proto == "h2" {
			hasH2 = true
		}
		if proto == "http/1.1" {
			hasH1 = true
		}
	}
	if !hasH2 && hasH1 {
		return "1.1"
	}
	return "2"
}

func getBaseRequestURL(options *V2RayXHTTPBaseOptions, dest M.Socksaddr, tlsConfig tls.Config) (url.URL, error) {
	var requestURL url.URL
	if tlsConfig == nil {
		requestURL.Scheme = "http"
	} else {
		requestURL.Scheme = "https"
	}
	requestURL.Host = options.Host
	if requestURL.Host == "" && tlsConfig != nil {
		requestURL.Host = tlsConfig.ServerName()
	}
	if requestURL.Host == "" {
		requestURL.Host = dest.AddrString()
	}
	requestURL.Path = options.Path
	if err := sHTTP.URLSetPath(&requestURL, options.Path); err != nil {
		return requestURL, E.New(err, "parse path")
	}
	if !strings.HasPrefix(requestURL.Path, "/") {
		requestURL.Path = "/" + requestURL.Path
	}
	requestURL.Path = options.GetNormalizedPath()
	requestURL.RawQuery = options.GetNormalizedQuery()
	return requestURL, nil
}

func isRealityConfig(tlsConfig tls.Config) bool {
	if tlsConfig == nil {
		return false
	}
	return strings.Contains(fmt.Sprintf("%T", tlsConfig), ".RealityClientConfig")
}

func createHTTPClient(dest M.Socksaddr, dialer N.Dialer, options *V2RayXHTTPBaseOptions, tlsConfig tls.Config) DialerClient {
	httpVersion := decideHTTPVersion(tlsConfig)
	dialContext := func(ctxInner context.Context) (net.Conn, error) {
		conn, err := dialer.DialContext(ctxInner, "tcp", dest)
		if err != nil {
			return nil, err
		}
		needTLS := tlsConfig != nil && httpVersion != "3"
		if needTLS {
			conn, err = tls.ClientHandshake(ctxInner, conn, tlsConfig)
			if err != nil {
				return nil, err
			}
		}
		return conn, nil
	}
	var keepAlivePeriod time.Duration
	if options.Xmux != nil {
		keepAlivePeriod = time.Duration(options.Xmux.HKeepAlivePeriod) * time.Second
	}
	var transport http.RoundTripper
	switch httpVersion {
	case "3":
		if keepAlivePeriod == 0 {
			keepAlivePeriod = net.QuicgoH3KeepAlivePeriod
		}
		if keepAlivePeriod < 0 {
			keepAlivePeriod = 0
		}
		quicConfig := &quic.Config{
			MaxIdleTimeout: net.ConnIdleTimeout,
			// these two are defaults of quic-go/http3. the default of quic-go (no
			// http3) is different, so it is hardcoded here for clarity.
			// https://github.com/quic-go/quic-go/blob/b8ea5c798155950fb5bbfdd06cad1939c9355878/http3/client.go#L36-L39
			MaxIncomingStreams: -1,
			KeepAlivePeriod:    keepAlivePeriod,
		}
		transport = &http3.Transport{
			QUICConfig: quicConfig,
			Dial: func(ctx context.Context, addr string, tlsCfg *gotls.Config, cfg *quic.Config) (*quic.Conn, error) {
				udpConn, dErr := dialer.DialContext(ctx, N.NetworkUDP, dest)
				if dErr != nil {
					return nil, dErr
				}
				return qtls.Dial(ctx, udpConn, tlsConfig, cfg)
			},
		}
	case "2":
		if keepAlivePeriod == 0 {
			keepAlivePeriod = net.ChromeH2KeepAlivePeriod
		}
		if keepAlivePeriod < 0 {
			keepAlivePeriod = 0
		}
		transport = &http2.Transport{
			DialTLSContext: func(ctxInner context.Context, network string, addr string, cfg *gotls.Config) (net.Conn, error) {
				return dialContext(ctxInner)
			},
			IdleConnTimeout: net.ConnIdleTimeout,
			ReadIdleTimeout: keepAlivePeriod,
			PingTimeout:     15 * time.Second,
			AllowHTTP:       true,
		}
	default:
		httpDialContext := func(ctxInner context.Context, network string, addr string) (net.Conn, error) {
			return dialContext(ctxInner)
		}
		transport = &http.Transport{
			DialTLSContext:  httpDialContext,
			DialContext:     httpDialContext,
			IdleConnTimeout: net.ConnIdleTimeout,
			// chunked transfer download with KeepAlives is buggy with
			// http.Client and our custom dial context.
			DisableKeepAlives: true,
		}
	}
	client := &DefaultDialerClient{
		options: options,
		client: &http.Client{
			Transport: transport,
		},
		httpVersion:    httpVersion,
		dialUploadConn: dialContext,
	}
	return client
}
