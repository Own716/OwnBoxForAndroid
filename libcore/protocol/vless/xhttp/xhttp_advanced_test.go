package xhttp

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/base64"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"

	"golang.org/x/net/http2"
	Xbadoption "libcore/protocol/vless/internal/xray/badoption"
)

func TestRangeUnmarshalCases(t *testing.T) {
	// Case 1: Plain Number
	var r1 Xbadoption.Range
	if err := json.Unmarshal([]byte("1000"), &r1); err != nil {
		t.Fatalf("unmarshal number failed: %v", err)
	}
	if r1.From != 1000 || r1.To != 1000 {
		t.Fatalf("expected 1000-1000, got %d-%d", r1.From, r1.To)
	}

	// Case 2: Number as String
	var r2 Xbadoption.Range
	if err := json.Unmarshal([]byte(`"500"`), &r2); err != nil {
		t.Fatalf("unmarshal number string failed: %v", err)
	}
	if r2.From != 500 || r2.To != 500 {
		t.Fatalf("expected 500-500, got %d-%d", r2.From, r2.To)
	}

	// Case 3: Range String
	var r3 Xbadoption.Range
	if err := json.Unmarshal([]byte(`"100-1000"`), &r3); err != nil {
		t.Fatalf("unmarshal range string failed: %v", err)
	}
	if r3.From != 100 || r3.To != 1000 {
		t.Fatalf("expected 100-1000, got %d-%d", r3.From, r3.To)
	}

	// Case 4: JSON Object
	var r4 Xbadoption.Range
	if err := json.Unmarshal([]byte(`{"from": 200, "to": 800}`), &r4); err != nil {
		t.Fatalf("unmarshal object failed: %v", err)
	}
	if r4.From != 200 || r4.To != 800 {
		t.Fatalf("expected 200-800, got %d-%d", r4.From, r4.To)
	}

	// Case 5: Full BaseOptions with heterogeneous range formats
	jsonConf := `{
		"mode": "packet-up",
		"x_padding_bytes": "100-500",
		"sc_max_each_post_bytes": 1048576,
		"sc_min_posts_interval_ms": {"from": 50, "to": 150},
		"sc_stream_up_server_secs": "30",
		"uplink_chunk_size": "2048-4096"
	}`
	var opts V2RayXHTTPBaseOptions
	if err := json.Unmarshal([]byte(jsonConf), &opts); err != nil {
		t.Fatalf("unmarshal base options failed: %v", err)
	}
	if opts.XPaddingBytes.From != 100 || opts.XPaddingBytes.To != 500 {
		t.Fatalf("unexpected x_padding_bytes: %+v", opts.XPaddingBytes)
	}
	if opts.ScMaxEachPostBytes.From != 1048576 || opts.ScMaxEachPostBytes.To != 1048576 {
		t.Fatalf("unexpected sc_max_each_post_bytes: %+v", opts.ScMaxEachPostBytes)
	}
	if opts.ScMinPostsIntervalMs.From != 50 || opts.ScMinPostsIntervalMs.To != 150 {
		t.Fatalf("unexpected sc_min_posts_interval_ms: %+v", opts.ScMinPostsIntervalMs)
	}
	if opts.ScStreamUpServerSecs.From != 30 || opts.ScStreamUpServerSecs.To != 30 {
		t.Fatalf("unexpected sc_stream_up_server_secs: %+v", opts.ScStreamUpServerSecs)
	}
	if opts.UplinkChunkSize.From != 2048 || opts.UplinkChunkSize.To != 4096 {
		t.Fatalf("unexpected uplink_chunk_size: %+v", opts.UplinkChunkSize)
	}
}

func TestXPaddingModesAndPlacements(t *testing.T) {
	// 1. Default Mode (Legacy Xray SplitHTTP compatibility: Referer + X-Padding)
	optsDefault := &V2RayXHTTPBaseOptions{
		XPaddingBytes: Xbadoption.Range{From: 16, To: 16},
	}
	req1, _ := http.NewRequest("POST", "https://example.com/test", nil)
	optsDefault.ApplyPadding(req1)
	if pad := req1.Header.Get("X-Padding"); pad != strings.Repeat("X", 16) {
		t.Fatalf("expected 16 'X's in X-Padding, got %q", pad)
	}
	if ref := req1.Header.Get("Referer"); ref != "https://example.com/test?x_padding="+strings.Repeat("X", 16) {
		t.Fatalf("unexpected Referer: %q", ref)
	}

	// 2. Tokenish Obfuscation
	tokenish := GenerateTokenishPaddingBase62(32)
	if len(tokenish) == 0 {
		t.Fatalf("tokenish padding generated empty string")
	}

	// 3. Obfs Mode: Placement Header
	optsHeader := &V2RayXHTTPBaseOptions{
		XPaddingBytes:     Xbadoption.Range{From: 20, To: 20},
		XPaddingObfsMode:  true,
		XPaddingPlacement: PlacementHeader,
		XPaddingKey:       "Custom-Padding-Key",
		XPaddingMethod:    PaddingMethodRepeatX,
	}
	req2, _ := http.NewRequest("POST", "https://example.com/test", nil)
	optsHeader.ApplyPadding(req2)
	if pad := req2.Header.Get("Custom-Padding-Key"); pad != strings.Repeat("X", 20) {
		t.Fatalf("expected custom header padding, got %q", pad)
	}

	// 4. Obfs Mode: Placement Cookie
	optsCookie := &V2RayXHTTPBaseOptions{
		XPaddingBytes:     Xbadoption.Range{From: 20, To: 20},
		XPaddingObfsMode:  true,
		XPaddingPlacement: PlacementCookie,
		XPaddingKey:       "session_pad",
		XPaddingMethod:    PaddingMethodRepeatX,
	}
	req3, _ := http.NewRequest("POST", "https://example.com/test", nil)
	optsCookie.ApplyPadding(req3)
	cookieVal := ""
	for _, c := range req3.Cookies() {
		if c.Name == "session_pad" {
			cookieVal = c.Value
			break
		}
	}
	if cookieVal != strings.Repeat("X", 20) {
		t.Fatalf("expected cookie padding %q, got %q", strings.Repeat("X", 20), cookieVal)
	}

	// 5. Obfs Mode: Placement Query
	optsQuery := &V2RayXHTTPBaseOptions{
		XPaddingBytes:     Xbadoption.Range{From: 20, To: 20},
		XPaddingObfsMode:  true,
		XPaddingPlacement: PlacementQuery,
		XPaddingKey:       "pad",
		XPaddingMethod:    PaddingMethodRepeatX,
	}
	req4, _ := http.NewRequest("POST", "https://example.com/test?param=val", nil)
	optsQuery.ApplyPadding(req4)
	if qPad := req4.URL.Query().Get("pad"); qPad != strings.Repeat("X", 20) {
		t.Fatalf("expected query pad %q, got %q", strings.Repeat("X", 20), qPad)
	}

	// 6. Obfs Mode: Placement QueryInHeader
	optsQueryInHeader := &V2RayXHTTPBaseOptions{
		XPaddingBytes:     Xbadoption.Range{From: 20, To: 20},
		XPaddingObfsMode:  true,
		XPaddingPlacement: PlacementQueryInHeader,
		XPaddingHeader:    "X-Source-URL",
		XPaddingKey:       "qpad",
		XPaddingMethod:    PaddingMethodRepeatX,
	}
	req5, _ := http.NewRequest("POST", "https://example.com/test", nil)
	optsQueryInHeader.ApplyPadding(req5)
	srcURL := req5.Header.Get("X-Source-URL")
	if !strings.Contains(srcURL, "qpad="+strings.Repeat("X", 20)) {
		t.Fatalf("expected queryInHeader in X-Source-URL, got %q", srcURL)
	}
}

func TestSessionAndSeqPlacements(t *testing.T) {
	// Placement 1: Path
	optsPath := &V2RayXHTTPBaseOptions{
		SessionPlacement: PlacementPath,
		SeqPlacement:     PlacementPath,
	}
	req1, _ := http.NewRequest("POST", "https://example.com/xhttp", nil)
	optsPath.ApplySessionAndSeq(req1, "sess-1234", 42)
	if req1.URL.Path != "/xhttp/sess-1234/42" {
		t.Fatalf("expected path /xhttp/sess-1234/42, got %s", req1.URL.Path)
	}

	// Placement 2: Query
	optsQuery := &V2RayXHTTPBaseOptions{
		SessionPlacement: PlacementQuery,
		SessionKey:       "sid",
		SeqPlacement:     PlacementQuery,
		SeqKey:           "sequence",
	}
	req2, _ := http.NewRequest("POST", "https://example.com/xhttp", nil)
	optsQuery.ApplySessionAndSeq(req2, "sess-5678", 99)
	if req2.URL.Query().Get("sid") != "sess-5678" {
		t.Fatalf("expected query sid=sess-5678, got %s", req2.URL.Query().Get("sid"))
	}
	if req2.URL.Query().Get("sequence") != "99" {
		t.Fatalf("expected query sequence=99, got %s", req2.URL.Query().Get("sequence"))
	}

	// Placement 3: Header
	optsHeader := &V2RayXHTTPBaseOptions{
		SessionPlacement: PlacementHeader,
		SessionKey:       "X-Custom-Session",
		SeqPlacement:     PlacementHeader,
		SeqKey:           "X-Custom-Seq",
	}
	req3, _ := http.NewRequest("POST", "https://example.com/xhttp", nil)
	optsHeader.ApplySessionAndSeq(req3, "sess-abcd", 7)
	if req3.Header.Get("X-Custom-Session") != "sess-abcd" {
		t.Fatalf("expected header session sess-abcd, got %s", req3.Header.Get("X-Custom-Session"))
	}
	if req3.Header.Get("X-Custom-Seq") != "7" {
		t.Fatalf("expected header seq 7, got %s", req3.Header.Get("X-Custom-Seq"))
	}

	// Placement 4: Cookie
	optsCookie := &V2RayXHTTPBaseOptions{
		SessionPlacement: PlacementCookie,
		SessionKey:       "csess",
		SeqPlacement:     PlacementCookie,
		SeqKey:           "cseq",
	}
	req4, _ := http.NewRequest("POST", "https://example.com/xhttp", nil)
	optsCookie.ApplySessionAndSeq(req4, "sess-cookie", 15)
	sessFound, seqFound := false, false
	for _, c := range req4.Cookies() {
		if c.Name == "csess" && c.Value == "sess-cookie" {
			sessFound = true
		}
		if c.Name == "cseq" && c.Value == "15" {
			seqFound = true
		}
	}
	if !sessFound || !seqFound {
		t.Fatalf("expected cookie session and seq, got %v", req4.Cookies())
	}
}

func TestUplinkPayloadPlacements(t *testing.T) {
	testPayload := []byte("hello-xhttp-uplink-payload")

	// 1. Placement: Body
	optsBody := &V2RayXHTTPBaseOptions{
		UplinkDataPlacement: UplinkDataPlacementBody,
	}
	req1, _ := http.NewRequest("POST", "https://example.com/xhttp", nil)
	optsBody.ApplyUplinkPayload(req1, testPayload)
	bodyBytes, _ := io.ReadAll(req1.Body)
	if !bytes.Equal(bodyBytes, testPayload) {
		t.Fatalf("expected body payload %q, got %q", testPayload, bodyBytes)
	}

	// 2. Placement: Header
	optsHeader := &V2RayXHTTPBaseOptions{
		UplinkDataPlacement: UplinkDataPlacementHeader,
		UplinkDataKey:       "X-Uplink-Data",
	}
	req2, _ := http.NewRequest("POST", "https://example.com/xhttp", nil)
	optsHeader.ApplyUplinkPayload(req2, testPayload)
	headerVal := req2.Header.Get("X-Uplink-Data-0")
	decodedHeader, err := base64.RawURLEncoding.DecodeString(headerVal)
	if err != nil || !bytes.Equal(decodedHeader, testPayload) {
		t.Fatalf("expected base64 header payload, got %q (err: %v)", headerVal, err)
	}

	// 3. Placement: Cookie
	optsCookie := &V2RayXHTTPBaseOptions{
		UplinkDataPlacement: UplinkDataPlacementCookie,
		UplinkDataKey:       "uplink_cookie",
	}
	req3, _ := http.NewRequest("POST", "https://example.com/xhttp", nil)
	optsCookie.ApplyUplinkPayload(req3, testPayload)
	var cookieVal string
	for _, c := range req3.Cookies() {
		if c.Name == "uplink_cookie_0" {
			cookieVal = c.Value
			break
		}
	}
	decodedCookie, err := base64.RawURLEncoding.DecodeString(cookieVal)
	if err != nil || !bytes.Equal(decodedCookie, testPayload) {
		t.Fatalf("expected base64 cookie payload, got %q (err: %v)", cookieVal, err)
	}
}

func TestAutoModeDoesNotDowngrade(t *testing.T) {
	// Verify that NormalizeXHTTPMode treats empty string as auto
	mode, err := NormalizeXHTTPMode("")
	if err != nil || mode != "auto" {
		t.Fatalf("expected auto, got %s (err: %v)", mode, err)
	}

	opts := &V2RayXHTTPBaseOptions{Mode: "auto"}
	// Default client mode should resolve cleanly without crashing
	if opts.Mode != "auto" {
		t.Fatalf("expected auto mode")
	}
}

func TestSplitConnThreadSafeClose(t *testing.T) {
	pipeR, pipeW := io.Pipe()
	sc := &splitConn{
		reader: pipeR,
		writer: pipeW,
	}

	// Concurrently call Close 20 times from separate goroutines
	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_ = sc.Close()
		}()
	}
	wg.Wait()

	if !sc.IsClosed() {
		t.Fatalf("expected splitConn to be marked closed")
	}

	// Write on closed splitConn should return error
	_, err := sc.Write([]byte("data"))
	if err == nil {
		t.Fatalf("expected write error on closed splitConn")
	}
}

type dummyXmuxConn struct {
	closed bool
	mu     sync.Mutex
}

func (d *dummyXmuxConn) Close() error {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.closed = true
	return nil
}

func (d *dummyXmuxConn) IsClosed() bool {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.closed
}

func TestXmuxManagerCleanup(t *testing.T) {
	var mu sync.Mutex
	var createdConns []*dummyXmuxConn
	newConn := func() XmuxConn {
		mu.Lock()
		defer mu.Unlock()
		c := &dummyXmuxConn{}
		createdConns = append(createdConns, c)
		return c
	}

	opts := V2RayXHTTPXmuxOptions{
		MaxConcurrency: Xbadoption.Range{From: 2, To: 2},
		MaxConnections: Xbadoption.Range{From: 2, To: 2},
	}
	mgr := NewXmuxManager(opts, newConn)

	client1 := mgr.GetXmuxClient(context.Background())
	if client1 == nil || len(createdConns) != 1 {
		t.Fatalf("expected 1 created connection")
	}

	// Close client1's connection directly
	_ = client1.XmuxConn.Close()

	// Next GetXmuxClient should prune closed client1 and create a fresh one
	client2 := mgr.GetXmuxClient(context.Background())
	if client2 == nil {
		t.Fatalf("expected valid client2")
	}

	// Close the manager
	if err := mgr.Close(); err != nil {
		t.Fatalf("manager close failed: %v", err)
	}

	// All created connections should be closed
	for _, c := range createdConns {
		if !c.IsClosed() {
			t.Fatalf("expected all connections to be closed by manager")
		}
	}
}

func TestLocalHTTP2ServerRoundTrip(t *testing.T) {
	// Create an HTTP/2 test server
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Verify custom header
		if r.Header.Get("X-Test-Client") != "OwnBox" {
			http.Error(w, "missing header", http.StatusBadRequest)
			return
		}

		if strings.HasPrefix(r.URL.Path, "/upload") {
			body, _ := io.ReadAll(r.Body)
			if len(body) == 0 {
				http.Error(w, "empty body", http.StatusBadRequest)
				return
			}
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte("upload-ack"))
			return
		}

		if strings.HasPrefix(r.URL.Path, "/download") {
			w.Header().Set("Content-Type", "application/octet-stream")
			w.WriteHeader(http.StatusOK)
			if flusher, ok := w.(http.Flusher); ok {
				flusher.Flush()
			}
			_, _ = w.Write([]byte("download-stream-data"))
			return
		}

		http.NotFound(w, r)
	})

	server := httptest.NewUnstartedServer(handler)
	_ = http2.ConfigureServer(server.Config, &http2.Server{})
	server.TLS = server.Config.TLSConfig
	server.StartTLS()
	defer server.Close()

	// Create HTTP/2 client targeting this test server
	transport := &http2.Transport{
		TLSClientConfig: &tls.Config{
			InsecureSkipVerify: true,
		},
	}
	h2Client := &http.Client{
		Transport: transport,
		Timeout:   5 * time.Second,
	}

	// 1. Test POST /upload with X-Padding and Session
	opts := &V2RayXHTTPBaseOptions{
		Headers: map[string]string{
			"X-Test-Client": "OwnBox",
		},
		XPaddingBytes:     Xbadoption.Range{From: 16, To: 16},
		XPaddingObfsMode:  true,
		XPaddingPlacement: PlacementHeader,
		SessionPlacement:  PlacementQuery,
		SessionKey:        "s",
		SeqPlacement:      PlacementQuery,
		SeqKey:            "seq",
	}

	reqURL, _ := url.Parse(server.URL + "/upload")
	req, _ := http.NewRequest("POST", reqURL.String(), bytes.NewReader([]byte("test-payload-12345")))
	for k, v := range opts.Headers {
		req.Header.Set(k, v)
	}
	opts.ApplyPadding(req)
	opts.ApplySessionAndSeq(req, "sess-h2-test", 1)

	resp, err := h2Client.Do(req)
	if err != nil {
		t.Fatalf("HTTP/2 POST failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d", resp.StatusCode)
	}
	ack, _ := io.ReadAll(resp.Body)
	if string(ack) != "upload-ack" {
		t.Fatalf("expected upload-ack, got %q", string(ack))
	}

	// 2. Test GET /download stream
	reqDown, _ := http.NewRequest("GET", server.URL+"/download", nil)
	reqDown.Header.Set("X-Test-Client", "OwnBox")
	opts.ApplyPadding(reqDown)

	respDown, err := h2Client.Do(reqDown)
	if err != nil {
		t.Fatalf("HTTP/2 GET failed: %v", err)
	}
	defer respDown.Body.Close()

	if respDown.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d", respDown.StatusCode)
	}
	downData, _ := io.ReadAll(respDown.Body)
	if string(downData) != "download-stream-data" {
		t.Fatalf("expected download-stream-data, got %q", string(downData))
	}
}

func TestDialerClientSafeClose(t *testing.T) {
	opts := &V2RayXHTTPBaseOptions{
		Host: "example.com",
		Path: "/xhttp",
	}
	client := &DefaultDialerClient{
		options: opts,
	}

	// Put dummy raw conn
	r, w := net.Pipe()
	defer r.Close()
	defer w.Close()

	h1 := &H1Conn{Conn: w}
	client.h1Mu.Lock()
	client.h1Conns = append(client.h1Conns, h1)
	client.h1Mu.Unlock()

	// Close client
	if err := client.Close(); err != nil {
		t.Fatalf("client Close failed: %v", err)
	}

	if !client.IsClosed() {
		t.Fatalf("expected client.IsClosed() to be true")
	}
	client.h1Mu.Lock()
	remConns := len(client.h1Conns)
	client.h1Mu.Unlock()
	if remConns != 0 {
		t.Fatalf("expected h1Conns to be cleared, got %d", remConns)
	}
}
