package xhttp

import (
	"bytes"
	"encoding/base64"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
	Xbadoption "libcore/protocol/vless/internal/xray/badoption"
)

const V2RayTransportTypeXHTTP = "xhttp"

const (
	UplinkDataPlacementBody   = "body"
	UplinkDataPlacementHeader = PlacementHeader
	UplinkDataPlacementCookie = PlacementCookie
)

func NormalizeXHTTPMode(mode string) (string, error) {
	mode = strings.TrimSpace(mode)
	if mode == "" {
		return "auto", nil
	}
	switch mode {
	case "auto", "packet-up", "stream-up", "stream-one":
		return mode, nil
	default:
		return "", E.New("unsupported mode: ", mode)
	}
}

type V2RayXHTTPBaseOptions struct {
	Mode                 string                 `json:"mode,omitempty"`
	Host                 string                 `json:"host,omitempty"`
	Path                 string                 `json:"path,omitempty"`
	Headers              map[string]string      `json:"headers,omitempty"`
	DomainStrategy       option.DomainStrategy  `json:"domain_strategy,omitempty"`
	XPaddingBytes        Xbadoption.Range       `json:"x_padding_bytes"`
	XPaddingObfsMode     bool                   `json:"x_padding_obfs_mode,omitempty"`
	XPaddingKey          string                 `json:"x_padding_key,omitempty"`
	XPaddingHeader       string                 `json:"x_padding_header,omitempty"`
	XPaddingPlacement    string                 `json:"x_padding_placement,omitempty"`
	XPaddingMethod       string                 `json:"x_padding_method,omitempty"`
	UplinkHttpMethod     string                 `json:"uplink_http_method,omitempty"`
	SessionPlacement     string                 `json:"session_placement,omitempty"`
	SessionKey           string                 `json:"session_key,omitempty"`
	SeqPlacement         string                 `json:"seq_placement,omitempty"`
	SeqKey               string                 `json:"seq_key,omitempty"`
	UplinkDataPlacement  string                 `json:"uplink_data_placement,omitempty"`
	UplinkDataKey        string                 `json:"uplink_data_key,omitempty"`
	UplinkChunkSize      Xbadoption.Range       `json:"uplink_chunk_size"`
	NoGRPCHeader         bool                   `json:"no_grpc_header,omitempty"`
	NoSSEHeader          bool                   `json:"no_sse_header,omitempty"`
	ScMaxEachPostBytes   Xbadoption.Range       `json:"sc_max_each_post_bytes"`
	ScMinPostsIntervalMs Xbadoption.Range       `json:"sc_min_posts_interval_ms"`
	ScMaxBufferedPosts   int64                  `json:"sc_max_buffered_posts,omitempty"`
	ScStreamUpServerSecs Xbadoption.Range       `json:"sc_stream_up_server_secs"`
	Xmux                 *V2RayXHTTPXmuxOptions `json:"xmux,omitempty"`
}

type V2RayXHTTPOptions struct {
	V2RayXHTTPBaseOptions
	Download *V2RayXHTTPDownloadOptions `json:"download,omitempty"`
}

type V2RayXHTTPDownloadOptions struct {
	V2RayXHTTPBaseOptions
	option.ServerOptions
	option.OutboundTLSOptionsContainer
	Detour string `json:"detour,omitempty"`
}

func (c *V2RayXHTTPBaseOptions) GetNormalizedSessionPlacement() string {
	p := strings.ToLower(strings.TrimSpace(c.SessionPlacement))
	if p == "" {
		return PlacementPath
	}
	return p
}

func (c *V2RayXHTTPBaseOptions) GetNormalizedSessionKey() string {
	k := strings.TrimSpace(c.SessionKey)
	if k != "" {
		return k
	}
	if c.GetNormalizedSessionPlacement() == PlacementHeader {
		return "X-Session-ID"
	}
	return "session"
}

func (c *V2RayXHTTPBaseOptions) GetNormalizedSeqPlacement() string {
	p := strings.ToLower(strings.TrimSpace(c.SeqPlacement))
	if p == "" {
		if c.GetNormalizedSessionPlacement() == PlacementPath {
			return PlacementPath
		}
		return PlacementQuery
	}
	return p
}

func (c *V2RayXHTTPBaseOptions) GetNormalizedSeqKey() string {
	k := strings.TrimSpace(c.SeqKey)
	if k != "" {
		return k
	}
	if c.GetNormalizedSeqPlacement() == PlacementHeader {
		return "X-Seq"
	}
	return "seq"
}

func (c *V2RayXHTTPBaseOptions) GetNormalizedUplinkHTTPMethod() string {
	m := strings.ToUpper(strings.TrimSpace(c.UplinkHttpMethod))
	if m == "" {
		return "POST"
	}
	return m
}

func (c *V2RayXHTTPBaseOptions) GetNormalizedUplinkDataPlacement() string {
	p := strings.ToLower(strings.TrimSpace(c.UplinkDataPlacement))
	if p == "" {
		return "body"
	}
	return p
}

func (c *V2RayXHTTPBaseOptions) GetNormalizedUplinkDataKey() string {
	k := strings.TrimSpace(c.UplinkDataKey)
	if k != "" {
		return k
	}
	switch c.GetNormalizedUplinkDataPlacement() {
	case PlacementHeader:
		return "x-data"
	case PlacementCookie:
		return "data"
	default:
		return "data"
	}
}

func (c *V2RayXHTTPBaseOptions) GetNormalizedUplinkChunkSize() Xbadoption.Range {
	if c.UplinkChunkSize.To == 0 {
		switch c.GetNormalizedUplinkDataPlacement() {
		case PlacementCookie:
			return Xbadoption.Range{From: 3072, To: 3072}
		default:
			return Xbadoption.Range{From: 4096, To: 4096}
		}
	}
	return c.UplinkChunkSize
}

func (c *V2RayXHTTPBaseOptions) GetNormalizedPath() string {
	pathAndQuery := strings.SplitN(c.Path, "?", 2)
	path := pathAndQuery[0]
	if path == "" || path[0] != '/' {
		path = "/" + path
	}
	if c.GetNormalizedSessionPlacement() == PlacementPath || c.GetNormalizedSeqPlacement() == PlacementPath {
		if path[len(path)-1] != '/' {
			path = path + "/"
		}
	}
	return path
}

func (c *V2RayXHTTPBaseOptions) GetStreamOnePath() string {
	pathAndQuery := strings.SplitN(c.Path, "?", 2)
	path := pathAndQuery[0]
	if path == "" {
		return "/"
	}
	if path[0] != '/' {
		path = "/" + path
	}
	return path
}

func (c *V2RayXHTTPBaseOptions) GetNormalizedQuery() string {
	pathAndQuery := strings.SplitN(c.Path, "?", 2)
	query := ""
	if len(pathAndQuery) > 1 {
		query = pathAndQuery[1]
	}
	return query
}

func (c *V2RayXHTTPBaseOptions) GetRequestHeader(rawURL string) http.Header {
	header := http.Header{}
	for k, v := range c.Headers {
		header.Add(k, v)
	}
	req, err := http.NewRequest("GET", rawURL, nil)
	if err == nil {
		req.Header = header
		c.ApplyPadding(req)
		return req.Header
	}
	paddingLen := int(c.GetNormalizedXPaddingBytes().Rand())
	if paddingLen > 0 {
		paddingStr := strings.Repeat("X", paddingLen)
		if u, err := url.Parse(rawURL); err == nil {
			if u.RawQuery != "" {
				u.RawQuery += "&x_padding=" + paddingStr
			} else {
				u.RawQuery = "x_padding=" + paddingStr
			}
			header.Set("Referer", u.String())
		}
		header.Set("X-Padding", paddingStr)
	}
	return header
}

func (c *V2RayXHTTPBaseOptions) ApplySessionAndSeq(req *http.Request, sessionId string, seq int64) {
	if sessionId != "" {
		switch c.GetNormalizedSessionPlacement() {
		case PlacementPath:
			if !strings.HasSuffix(req.URL.Path, "/") {
				req.URL.Path += "/"
			}
			req.URL.Path += sessionId
		case PlacementQuery:
			q := req.URL.Query()
			q.Set(c.GetNormalizedSessionKey(), sessionId)
			req.URL.RawQuery = q.Encode()
		case PlacementHeader:
			req.Header.Set(c.GetNormalizedSessionKey(), sessionId)
		case PlacementCookie:
			req.AddCookie(&http.Cookie{
				Name:  c.GetNormalizedSessionKey(),
				Value: sessionId,
			})
		}
	}

	if seq >= 0 {
		seqStr := strconv.FormatInt(seq, 10)
		switch c.GetNormalizedSeqPlacement() {
		case PlacementPath:
			if !strings.HasSuffix(req.URL.Path, "/") {
				req.URL.Path += "/"
			}
			req.URL.Path += seqStr
		case PlacementQuery:
			q := req.URL.Query()
			q.Set(c.GetNormalizedSeqKey(), seqStr)
			req.URL.RawQuery = q.Encode()
		case PlacementHeader:
			req.Header.Set(c.GetNormalizedSeqKey(), seqStr)
		case PlacementCookie:
			req.AddCookie(&http.Cookie{
				Name:  c.GetNormalizedSeqKey(),
				Value: seqStr,
			})
		}
	}
}

// ApplyUplinkPayload applies payload into headers or cookies when uplink_data_placement is not "body".
// Returns true if payload should be sent in HTTP request body, or false if already embedded into headers/cookies.
func (c *V2RayXHTTPBaseOptions) ApplyUplinkPayload(req *http.Request, payload []byte) (inBody bool) {
	placement := c.GetNormalizedUplinkDataPlacement()
	if placement == "body" || len(payload) == 0 {
		if req.Body == nil && len(payload) > 0 {
			req.Body = io.NopCloser(bytes.NewReader(payload))
			req.ContentLength = int64(len(payload))
		}
		return true
	}

	encodedData := base64.RawURLEncoding.EncodeToString(payload)
	key := c.GetNormalizedUplinkDataKey()
	chunkSizeRange := c.GetNormalizedUplinkChunkSize()

	switch placement {
	case PlacementHeader:
		for i := 0; len(encodedData) > 0; i++ {
			chunkSize := min(int(chunkSizeRange.Rand()), len(encodedData))
			if chunkSize <= 0 {
				chunkSize = len(encodedData)
			}
			chunk := encodedData[:chunkSize]
			encodedData = encodedData[chunkSize:]
			headerKey := fmt.Sprintf("%s-%d", key, i)
			req.Header.Set(headerKey, chunk)
		}
		return false

	case PlacementCookie:
		for i := 0; len(encodedData) > 0; i++ {
			chunkSize := min(int(chunkSizeRange.Rand()), len(encodedData))
			if chunkSize <= 0 {
				chunkSize = len(encodedData)
			}
			chunk := encodedData[:chunkSize]
			encodedData = encodedData[chunkSize:]
			cookieName := fmt.Sprintf("%s_%d", key, i)
			req.AddCookie(&http.Cookie{Name: cookieName, Value: chunk})
		}
		return false

	default:
		return true
	}
}

func (c *V2RayXHTTPBaseOptions) GetNormalizedXPaddingBytes() Xbadoption.Range {
	if c.XPaddingBytes.To == 0 {
		return Xbadoption.Range{
			From: 100,
			To:   1000,
		}
	}
	return c.XPaddingBytes
}

func (c *V2RayXHTTPBaseOptions) GetNormalizedScMaxEachPostBytes() Xbadoption.Range {
	if c.ScMaxEachPostBytes.To == 0 {
		return Xbadoption.Range{
			From: 1000000,
			To:   1000000,
		}
	}
	return c.ScMaxEachPostBytes
}

func (c *V2RayXHTTPBaseOptions) GetNormalizedScMinPostsIntervalMs() Xbadoption.Range {
	if c.ScMinPostsIntervalMs.To == 0 {
		return Xbadoption.Range{
			From: 30,
			To:   30,
		}
	}
	return c.ScMinPostsIntervalMs
}

func (c *V2RayXHTTPBaseOptions) GetNormalizedScMaxBufferedPosts() int {
	if c.ScMaxBufferedPosts == 0 {
		return 30
	}
	return int(c.ScMaxBufferedPosts)
}

func (c *V2RayXHTTPBaseOptions) GetNormalizedScStreamUpServerSecs() Xbadoption.Range {
	if c.ScStreamUpServerSecs.To == 0 {
		return Xbadoption.Range{
			From: 20,
			To:   80,
		}
	}
	return c.ScStreamUpServerSecs
}

type V2RayXHTTPXmuxOptions struct {
	MaxConcurrency   Xbadoption.Range `json:"max_concurrency"`
	MaxConnections   Xbadoption.Range `json:"max_connections"`
	CMaxReuseTimes   Xbadoption.Range `json:"c_max_reuse_times"`
	HMaxRequestTimes Xbadoption.Range `json:"h_max_request_times"`
	HMaxReusableSecs Xbadoption.Range `json:"h_max_reusable_secs"`
	HKeepAlivePeriod int64            `json:"h_keep_alive_period"`
}

func (m V2RayXHTTPXmuxOptions) isZero() bool {
	return m == (V2RayXHTTPXmuxOptions{})
}

func (m *V2RayXHTTPXmuxOptions) Validate() error {
	if m.MaxConnections.To > 0 && m.MaxConcurrency.To > 0 {
		return E.New("maxConnections cannot be specified together with maxConcurrency")
	}
	return nil
}

func (m *V2RayXHTTPXmuxOptions) GetNormalizedMaxConcurrency() Xbadoption.Range {
	if m.isZero() {
		return Xbadoption.Range{From: 1, To: 1}
	}
	return m.MaxConcurrency
}

func (m *V2RayXHTTPXmuxOptions) GetNormalizedMaxConnections() Xbadoption.Range {
	return m.MaxConnections
}

func (m *V2RayXHTTPXmuxOptions) GetNormalizedCMaxReuseTimes() Xbadoption.Range {
	return m.CMaxReuseTimes
}

func (m *V2RayXHTTPXmuxOptions) GetNormalizedHMaxRequestTimes() Xbadoption.Range {
	if m.isZero() && m.HMaxRequestTimes.From == 0 && m.HMaxRequestTimes.To == 0 {
		return Xbadoption.Range{From: 600, To: 900}
	}
	return m.HMaxRequestTimes
}

func (m *V2RayXHTTPXmuxOptions) GetNormalizedHMaxReusableSecs() Xbadoption.Range {
	if m.isZero() && m.HMaxReusableSecs.From == 0 && m.HMaxReusableSecs.To == 0 {
		return Xbadoption.Range{From: 1800, To: 3000}
	}
	return m.HMaxReusableSecs
}
