// Package http 提供 sing-box "http" outbound 的覆盖实现：
// 在 TLS 启用时默认提供 ALPN ["h2", "http/1.1"]，握手后按协商结果分流
// CONNECT 协议（h2 → HTTP/2 CONNECT，否则 → 原有 HTTP/1.1 CONNECT）。
// 用于兼容 h2-only 的 HTTPS 代理节点（对齐 v2ray 系核心的既有行为）。
package http

import (
	std_bufio "bufio"
	"context"
	stdtls "crypto/tls"
	"encoding/base64"
	"io"
	"net"
	stdhttp "net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/common/tls"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/buf"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	sHTTP "github.com/sagernet/sing/protocol/http"
	"golang.org/x/net/http2"
)

// RegisterOutbound 以相同的类型名 "http" 覆盖注册，
// 须在 sing-box protocol/http.RegisterOutbound 之后调用。
func RegisterOutbound(registry *outbound.Registry) {
	outbound.Register[option.HTTPOutboundOptions](registry, C.TypeHTTP, NewOutbound)
}

var _ adapter.Outbound = (*Outbound)(nil)

type Outbound struct {
	outbound.Adapter
	logger     logger.ContextLogger
	client     *sHTTP.Client // 明文 HTTP 路径
	dialer     N.Dialer      // TLS detour dialer（TLS 启用时使用）
	serverAddr M.Socksaddr
	tlsEnabled bool
	username   string
	password   string
	host       string
	path       string
	headers    stdhttp.Header
}

func NewOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options option.HTTPOutboundOptions) (adapter.Outbound, error) {
	outboundDialer, err := dialer.New(ctx, options.DialerOptions, options.ServerIsDomain())
	if err != nil {
		return nil, err
	}
	tlsEnabled := options.TLS != nil && options.TLS.Enabled
	if tlsEnabled && len(options.TLS.ALPN) == 0 {
		// 用户未显式配置 ALPN 时默认提供 ["h2", "http/1.1"]，
		// 握手后按协商结果分流 CONNECT 协议。
		// 显式配置 ALPN 时尊重用户配置（例如填 http/1.1 可回退旧行为）。
		options.TLS.ALPN = []string{"h2", "http/1.1"}
	}
	// 官方 v1.13 签名：NewDialerFromOptions(ctx, logger, dialer, serverAddress, options)
	detour, err := tls.NewDialerFromOptions(ctx, logger, outboundDialer, options.Server, common.PtrValueOrDefault(options.TLS))
	if err != nil {
		return nil, err
	}
	headers := options.Headers.Build()
	var host string
	if headers != nil {
		host = headers.Get("Host")
	}
	// 注意：sHTTP.NewClient 内部会从 headers 中删除 Host（共享同一 map），
	// 之后 h.headers 即为不含 Host 的版本，可直接用于 TLS 分支。
	return &Outbound{
		Adapter: outbound.NewAdapterWithDialerOptions(C.TypeHTTP, tag, []string{N.NetworkTCP}, options.DialerOptions),
		logger:  logger,
		client: sHTTP.NewClient(sHTTP.Options{
			Dialer:   detour,
			Server:   options.ServerOptions.Build(),
			Username: options.Username,
			Password: options.Password,
			Path:     options.Path,
			Headers:  headers,
		}),
		dialer:     detour,
		serverAddr: options.ServerOptions.Build(),
		tlsEnabled: tlsEnabled,
		username:   options.Username,
		password:   options.Password,
		host:       host,
		path:       options.Path,
		headers:    headers,
	}, nil
}

func (h *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	h.logger.InfoContext(ctx, "outbound connection to ", destination)
	if !h.tlsEnabled {
		return h.client.DialContext(ctx, network, destination)
	}
	network = N.NetworkName(network)
	switch network {
	case N.NetworkTCP:
	case N.NetworkUDP:
		return nil, os.ErrInvalid
	default:
		return nil, E.Extend(N.ErrUnknownNetwork, network)
	}
	conn, err := h.dialer.DialContext(ctx, N.NetworkTCP, h.serverAddr)
	if err != nil {
		return nil, err
	}
	// TLS 握手完成后按 ALPN 协商结果分流：
	// 协商到 h2 → HTTP/2 CONNECT；否则保持原有 HTTP/1.1 CONNECT 行为。
	// 注意：std tls / uTLS / REALITY 包装连接均暴露
	// ConnectionState() stdtls.ConnectionState，这里不对具体类型断言。
	if stateConn, ok := conn.(interface {
		ConnectionState() stdtls.ConnectionState
	}); ok && stateConn.ConnectionState().NegotiatedProtocol == "h2" {
		return h.dialH2(ctx, conn, destination)
	}
	return h.dialHTTP1(conn, destination)
}

func (h *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return nil, os.ErrInvalid
}

// dialHTTP1 在已建立的 TLS 连接上执行 HTTP/1.1 CONNECT，
// 行为与 sing protocol/http Client 保持一致。
func (h *Outbound) dialHTTP1(conn net.Conn, destination M.Socksaddr) (net.Conn, error) {
	request := &stdhttp.Request{
		Method: stdhttp.MethodConnect,
		Header: stdhttp.Header{
			"Proxy-Connection": []string{"Keep-Alive"},
		},
	}
	if h.host != "" && h.host != destination.Fqdn {
		if h.path != "" {
			_ = conn.Close()
			return nil, E.New("Host header and path are not allowed at the same time")
		}
		request.Host = h.host
		request.URL = &url.URL{Opaque: destination.String()}
	} else {
		request.URL = &url.URL{Host: destination.String()}
	}
	if h.path != "" {
		err := sHTTP.URLSetPath(request.URL, h.path)
		if err != nil {
			_ = conn.Close()
			return nil, err
		}
	}
	for key, valueList := range h.headers {
		request.Header.Set(key, valueList[0])
		for _, value := range valueList[1:] {
			request.Header.Add(key, value)
		}
	}
	if h.username != "" {
		auth := h.username + ":" + h.password
		request.Header.Add("Proxy-Authorization", "Basic "+base64.StdEncoding.EncodeToString([]byte(auth)))
	}
	err := request.Write(conn)
	if err != nil {
		conn.Close()
		return nil, err
	}
	reader := std_bufio.NewReader(conn)
	response, err := stdhttp.ReadResponse(reader, request)
	if err != nil {
		conn.Close()
		return nil, err
	}
	if response.StatusCode == stdhttp.StatusOK {
		if reader.Buffered() > 0 {
			buffer := buf.NewSize(reader.Buffered())
			_, err = buffer.ReadFullFrom(reader, buffer.FreeLen())
			if err != nil {
				conn.Close()
				return nil, err
			}
			conn = bufio.NewCachedConn(conn, buffer)
		}
		return conn, nil
	}
	conn.Close()
	switch response.StatusCode {
	case stdhttp.StatusProxyAuthRequired:
		return nil, E.New("authentication required")
	case stdhttp.StatusMethodNotAllowed:
		return nil, E.New("method not allowed")
	default:
		return nil, E.New("unexpected status: ", response.Status)
	}
}

// dialH2 在已协商 ALPN=h2 的 TLS 连接上执行 HTTP/2 CONNECT，
// 上行流以 io.Pipe 作为请求 Body，响应 Body 即下行流。
func (h *Outbound) dialH2(ctx context.Context, conn net.Conn, destination M.Socksaddr) (net.Conn, error) {
	transport := &http2.Transport{
		// 复用已握手完成的连接，不再让 Transport 自己拨号。
		// （设置 DialTLSContext 时 Transport 不再自行校验 ALPN。）
		DialTLSContext: func(ctx context.Context, network, addr string, cfg *stdtls.Config) (net.Conn, error) {
			return conn, nil
		},
	}
	pipeReader, pipeWriter := io.Pipe()
	request := &stdhttp.Request{
		Method: stdhttp.MethodConnect,
		URL:    &url.URL{Scheme: "https", Host: destination.String()},
		Host:   destination.String(),
		Header: make(stdhttp.Header),
		Body:   pipeReader,
	}
	if h.host != "" && h.host != destination.Fqdn {
		request.Host = h.host
	}
	for key, valueList := range h.headers {
		// 过滤 HTTP/1.1 连接级头（RFC 7540 8.1.2.2），h2 中不合法。
		switch strings.ToLower(key) {
		case "connection", "proxy-connection", "keep-alive", "upgrade", "transfer-encoding":
			continue
		}
		request.Header.Set(key, valueList[0])
		for _, value := range valueList[1:] {
			request.Header.Add(key, value)
		}
	}
	if h.username != "" || h.password != "" {
		auth := h.username + ":" + h.password
		request.Header.Set("Proxy-Authorization", "Basic "+base64.StdEncoding.EncodeToString([]byte(auth)))
	}
	response, err := transport.RoundTrip(request.WithContext(ctx))
	if err != nil {
		pipeReader.Close()
		pipeWriter.Close()
		conn.Close()
		return nil, err
	}
	if response.StatusCode != stdhttp.StatusOK {
		response.Body.Close()
		pipeReader.Close()
		pipeWriter.Close()
		conn.Close()
		switch response.StatusCode {
		case stdhttp.StatusProxyAuthRequired:
			return nil, E.New("authentication required")
		case stdhttp.StatusMethodNotAllowed:
			return nil, E.New("method not allowed")
		default:
			return nil, E.New("unexpected status: ", response.Status)
		}
	}
	return &h2TunnelConn{
		Conn:   conn,
		reader: response.Body,
		writer: pipeWriter,
	}, nil
}

// h2TunnelConn 将 h2 CONNECT 流适配为 net.Conn：
// 读自响应 Body，写入 io.Pipe（作为请求 Body 上行）。
type h2TunnelConn struct {
	net.Conn // LocalAddr/RemoteAddr 委托给底层 TLS 连接
	reader   io.ReadCloser
	writer   *io.PipeWriter
	closeOnce sync.Once
}

func (c *h2TunnelConn) Read(b []byte) (int, error)  { return c.reader.Read(b) }
func (c *h2TunnelConn) Write(b []byte) (int, error) { return c.writer.Write(b) }

func (c *h2TunnelConn) Close() error {
	// 幂等：写关 = 上行流结束；关 reader = 取消下行流；底层连接一并关闭。
	c.closeOnce.Do(func() {
		c.writer.Close()
		c.reader.Close()
		c.Conn.Close()
	})
	return nil
}

// h2 流无截止时间语义，按需 no-op。
func (c *h2TunnelConn) SetDeadline(t time.Time) error      { return nil }
func (c *h2TunnelConn) SetReadDeadline(t time.Time) error  { return nil }
func (c *h2TunnelConn) SetWriteDeadline(t time.Time) error { return nil }
