package urltest

import (
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/sagernet/sing-box/adapter"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	"golang.org/x/net/http2"
)

const (
	DefaultFallbackURL = "https://www.gstatic.com/generate_204"
	DefaultCFURL       = "https://cp.cloudflare.com/generate_204"
	BrowserUserAgent   = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)

func GetFallbackLink(primaryLink string) string {
	if strings.Contains(primaryLink, "cloudflare.com") {
		return DefaultFallbackURL
	}
	return DefaultCFURL
}

// ProbeOutbound 对齐真实节点测速基准（两阶段 Keep-Alive 预热探测）：
// 阶段一（预热）：通过 detour 建立代理连接并完成目标端 TLS 握手，消除冷启动握手带来的虚高延迟；
// 阶段二（测量）：复用连接池中的保活连接发送探测，测得纯 1-RTT 真实往返时延（~100-250ms）；
// 若主探测地址不可达，自动以备用地址重试，杜绝 CDN 兼容性造成的误报超时。
func ProbeOutbound(ctx context.Context, detour adapter.Outbound, link string, timeout time.Duration) (uint16, error) {
	if detour == nil {
		return 0, E.New("nil detour")
	}
	if link == "" {
		link = DefaultCFURL
	}
	if timeout <= 0 {
		timeout = 3500 * time.Millisecond
	}

	primaryTimeout := timeout
	fallbackTimeout := 2000 * time.Millisecond
	if timeout > 3500*time.Millisecond {
		primaryTimeout = timeout - 1500*time.Millisecond
	}

	delay, err := probeSingleURL(ctx, detour, link, primaryTimeout)
	if err == nil && delay > 0 {
		return delay, nil
	}

	// 备选地址重试
	fbLink := GetFallbackLink(link)
	fbDelay, fbErr := probeSingleURL(ctx, detour, fbLink, fallbackTimeout)
	if fbErr == nil && fbDelay > 0 {
		return fbDelay, nil
	}

	if err != nil {
		return 0, err
	}
	return 0, fbErr
}

func probeSingleURL(parentCtx context.Context, detour adapter.Outbound, link string, timeout time.Duration) (uint16, error) {
	linkURL, err := url.Parse(link)
	if err != nil {
		return 0, E.Cause(err, "parse test link")
	}
	hostname := linkURL.Hostname()
	port := linkURL.Port()
	if port == "" {
		switch linkURL.Scheme {
		case "http":
			port = "80"
		case "https":
			port = "443"
		default:
			port = "443"
		}
	}

	ctx, cancel := context.WithTimeout(parentCtx, timeout)
	defer cancel()

	transport := &http.Transport{
		DialContext: func(dialCtx context.Context, network, addr string) (net.Conn, error) {
			return detour.DialContext(dialCtx, network, M.ParseSocksaddr(addr))
		},
		TLSClientConfig: &tls.Config{
			ServerName:         hostname,
			InsecureSkipVerify: true,
			NextProtos:         []string{"h2", "http/1.1"},
		},
		ForceAttemptHTTP2: true,
		DisableKeepAlives: false,
		MaxIdleConns:      3,
		IdleConnTimeout:   10 * time.Second,
	}
	_ = http2.ConfigureTransport(transport)
	defer transport.CloseIdleConnections()

	client := &http.Client{
		Transport: transport,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}

	// 阶段一：以标准浏览器 UA 发起 GET 预热连接
	req1, err := http.NewRequestWithContext(ctx, http.MethodGet, link, nil)
	if err != nil {
		return 0, err
	}
	req1.Header.Set("User-Agent", BrowserUserAgent)

	start1 := time.Now()
	resp1, err := client.Do(req1)
	if err == nil {
		_, _ = io.CopyN(io.Discard, resp1.Body, 8192)
		_ = resp1.Body.Close()
		if resp1.StatusCode >= 500 {
			err = fmt.Errorf("HTTP error %d", resp1.StatusCode)
		}
	}
	if err != nil {
		return 0, err
	}
	pass1 := time.Since(start1)

	// 阶段二：复用保活长连接，测量真实 1-RTT 时延
	req2, err2 := http.NewRequestWithContext(ctx, http.MethodGet, link, nil)
	if err2 == nil {
		req2.Header.Set("User-Agent", BrowserUserAgent)
		start2 := time.Now()
		resp2, err2Do := client.Do(req2)
		if err2Do == nil {
			_, _ = io.CopyN(io.Discard, resp2.Body, 8192)
			_ = resp2.Body.Close()
			if resp2.StatusCode < 500 {
				lat := uint16(time.Since(start2).Milliseconds())
				if lat == 0 {
					lat = 1
				}
				return lat, nil
			}
		}
	}

	// 远端不支持 Keep-Alive 时，回退至阶段一耗时
	lat := uint16(pass1.Milliseconds())
	if lat == 0 {
		lat = 1
	}
	return lat, nil
}
