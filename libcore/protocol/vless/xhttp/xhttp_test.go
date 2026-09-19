package xhttp

import (
	"io"
	"testing"
	"time"

	"github.com/sagernet/sing-box/common/tls"
	Xbadoption "libcore/protocol/vless/internal/xray/badoption"
)

type mockTLSConfig struct {
	tls.Config
	nextProtos []string
	serverName string
}

func (m *mockTLSConfig) ServerName() string {
	return m.serverName
}

func (m *mockTLSConfig) NextProtos() []string {
	return m.nextProtos
}

func (m *mockTLSConfig) SetNextProtos(protos []string) {
	m.nextProtos = protos
}

func (m *mockTLSConfig) Clone() tls.Config {
	return &mockTLSConfig{nextProtos: append([]string(nil), m.nextProtos...), serverName: m.serverName}
}

func TestDecideHTTPVersion(t *testing.T) {
	if v := decideHTTPVersion(nil); v != "1.1" {
		t.Fatalf("expected 1.1 for nil tlsConfig, got %s", v)
	}

	cfgEmpty := &mockTLSConfig{}
	if v := decideHTTPVersion(cfgEmpty); v != "2" {
		t.Fatalf("expected 2 for empty NextProtos, got %s", v)
	}
	if len(cfgEmpty.nextProtos) != 2 || cfgEmpty.nextProtos[0] != "h2" || cfgEmpty.nextProtos[1] != "http/1.1" {
		t.Fatalf("expected default [h2, http/1.1], got %v", cfgEmpty.nextProtos)
	}

	cfgH1 := &mockTLSConfig{nextProtos: []string{"http/1.1"}}
	if v := decideHTTPVersion(cfgH1); v != "1.1" {
		t.Fatalf("expected 1.1 for [http/1.1], got %s", v)
	}

	cfgH2 := &mockTLSConfig{nextProtos: []string{"h2", "http/1.1"}}
	if v := decideHTTPVersion(cfgH2); v != "2" {
		t.Fatalf("expected 2 for [h2, http/1.1], got %s", v)
	}

	cfgH3 := &mockTLSConfig{nextProtos: []string{"h3"}}
	if v := decideHTTPVersion(cfgH3); v != "3" {
		t.Fatalf("expected 3 for [h3], got %s", v)
	}
}

func TestGetRequestHeader(t *testing.T) {
	opts := &V2RayXHTTPBaseOptions{
		Headers: map[string]string{
			"Custom-Header": "OwnBoxValue",
		},
		XPaddingBytes: Xbadoption.Range{From: 10, To: 10},
	}

	h1 := opts.GetRequestHeader("https://example.com/xhttp")
	if h1.Get("Custom-Header") != "OwnBoxValue" {
		t.Fatalf("expected Custom-Header to be OwnBoxValue")
	}
	if len(h1.Get("X-Padding")) != 10 {
		t.Fatalf("expected 10 X padding bytes, got %d", len(h1.Get("X-Padding")))
	}
	if ref := h1.Get("Referer"); ref != "https://example.com/xhttp?x_padding=XXXXXXXXXX" {
		t.Fatalf("unexpected referer: %s", ref)
	}

	h2 := opts.GetRequestHeader("https://example.com/xhttp?token=123&env=prod")
	if ref := h2.Get("Referer"); ref != "https://example.com/xhttp?token=123&env=prod&x_padding=XXXXXXXXXX" {
		t.Fatalf("unexpected referer with existing query params: %s", ref)
	}
}

func TestWaitReadCloser(t *testing.T) {
	w := &WaitReadCloser{Wait: make(chan struct{})}
	r, pw := io.Pipe()
	w.Set(r)

	go func() {
		pw.Write([]byte("hello xhttp"))
		pw.Close()
	}()

	buf := make([]byte, 32)
	n, err := w.Read(buf)
	if err != nil {
		t.Fatalf("read failed: %v", err)
	}
	if string(buf[:n]) != "hello xhttp" {
		t.Fatalf("unexpected content: %s", string(buf[:n]))
	}
	w.Close()

	wErr := &WaitReadCloser{Wait: make(chan struct{})}
	testErr := io.ErrUnexpectedEOF
	wErr.SetErr(testErr)
	wErr.Close()

	n, err = wErr.Read(buf)
	if err != testErr {
		t.Fatalf("expected testErr, got %v", err)
	}

	wClose := &WaitReadCloser{Wait: make(chan struct{})}
	done := make(chan struct{})
	go func() {
		_, err := wClose.Read(buf)
		if err == nil {
			t.Errorf("expected error on closed waitReader")
		}
		close(done)
	}()
	time.Sleep(50 * time.Millisecond)
	wClose.Close()

	select {
	case <-done:
	case <-time.After(1 * time.Second):
		t.Fatalf("Read did not unblock after Close()")
	}
}
