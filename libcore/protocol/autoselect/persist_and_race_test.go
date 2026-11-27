package autoselect

import (
	"context"
	"errors"
	"net"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing/common/buf"
	"github.com/sagernet/sing/common/metadata"
)

func TestAutoSelectPersistRoundTripAcrossRestart(t *testing.T) {
	persistCacheFileOverride = filepath.Join(t.TempDir(), "autoselect_cache.json")
	t.Cleanup(func() { persistCacheFileOverride = "" })

	mgr1 := &mockOutboundManager{
		items: map[string]adapter.Outbound{
			"hk-1": newMockMember("hk-1"),
			"hk-2": newMockMember("hk-2"),
		},
	}
	ctx1 := newAutoSelectTestContext(mgr1)
	defer func() { _ = mgr1.Close() }()

	g1, err := NewAutoSelect(ctx1, nil, nil, "group-main", AutoSelectOptions{
		Outbounds:     []string{"hk-1", "hk-2"},
		CacheKey:      "profile_546_test",
		SelectionMode: SelectionModeLatency,
	})
	if err != nil {
		t.Fatalf("NewAutoSelect g1 failed: %v", err)
	}
	baseNow := time.Now()
	g1.nowFn = func() time.Time { return baseNow }
	g1.scorer.recordSample("hk-1", 120, nil, baseNow)
	g1.scorer.recordSample("hk-2", 25, nil, baseNow)
	g1.scorer.evaluate(baseNow, g1.passive)
	if g1.Now() != "hk-2" {
		t.Fatalf("expected hk-2 active before close, got %q", g1.Now())
	}
	g1.passive.RecordSuccess("hk-2", "api.telegram.org", 18*time.Millisecond)
	_ = g1.Close()

	mgr2 := &mockOutboundManager{
		items: map[string]adapter.Outbound{
			"hk-1": newMockMember("hk-1"),
			"hk-2": newMockMember("hk-2"),
		},
	}
	ctx2 := newAutoSelectTestContext(mgr2)
	g2, err := NewAutoSelect(ctx2, nil, nil, "group-main", AutoSelectOptions{
		Outbounds:     []string{"hk-1", "hk-2"},
		CacheKey:      "profile_546_test",
		SelectionMode: SelectionModeLatency,
	})
	if err != nil {
		t.Fatalf("NewAutoSelect g2 failed: %v", err)
	}
	defer func() { _ = g2.Close() }()

	if g2.Now() != "hk-2" {
		t.Fatalf("expected restored active node hk-2 immediately after restart, got %q", g2.Now())
	}
	if !g2.scorer.hasWarmState() || !g2.scorer.hasAnyVerifiedHealthy() {
		t.Fatalf("expected warm state and healthy member restored")
	}
	if pref := g2.passive.PreferredSiteNode("web.telegram.org", baseNow); pref != "hk-2" {
		t.Fatalf("expected persisted site memory telegram.org -> hk-2, got %q", pref)
	}
	if h := g2.ctx; h != nil {
		hist := urltest.NewHistoryStorage()
		_ = hist
	}
}

func TestAutoSelectColdStartRaceBeatsSlowFirstNode(t *testing.T) {
	persistCacheFileOverride = filepath.Join(t.TempDir(), "race_cache.json")
	t.Cleanup(func() { persistCacheFileOverride = "" })

	slow := newMockMember("slow-1")
	fast := newMockMember("fast-2")

	var slowDials atomic.Int32
	slow.dialFn = func(ctx context.Context, network string, destination metadata.Socksaddr) (net.Conn, error) {
		slowDials.Add(1)
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(1500 * time.Millisecond):
			return nil, errors.New("slow timeout")
		}
	}
	fast.dialFn = func(ctx context.Context, network string, destination metadata.Socksaddr) (net.Conn, error) {
		c1, _ := net.Pipe()
		return c1, nil
	}

	mgr := &mockOutboundManager{
		items: map[string]adapter.Outbound{
			"slow-1": slow,
			"fast-2": fast,
		},
	}
	ctx := newAutoSelectTestContext(mgr)
	g, err := NewAutoSelect(ctx, nil, nil, "group-race", AutoSelectOptions{
		Outbounds:     []string{"slow-1", "fast-2"},
		SelectionMode: SelectionModeLatency,
	})
	if err != nil {
		t.Fatalf("NewAutoSelect failed: %v", err)
	}
	defer func() { _ = g.Close() }()

	start := time.Now()
	conn, err := g.DialContext(context.Background(), "tcp", metadata.ParseSocksaddr("example.com:443"))
	elapsed := time.Since(start)
	if err != nil {
		t.Fatalf("DialContext cold race failed: %v", err)
	}
	_ = conn.Close()

	if slowDials.Load() == 0 {
		t.Fatalf("expected slow-1 to be attempted first")
	}
	if elapsed > 600*time.Millisecond {
		t.Fatalf("cold-start Happy-Eyeballs race took too long: %s", elapsed)
	}
}

func TestPassiveConnReadWriteBufferAndReplaceableContract(t *testing.T) {
	c1, c2 := net.Pipe()
	defer c1.Close()
	defer c2.Close()

	var succeeded atomic.Bool
	pConn := newPassiveConn(c1, func(ttfb time.Duration) {
		succeeded.Store(true)
	}, nil, time.Now)

	if pConn.WriterReplaceable() {
		t.Fatalf("WriterReplaceable must stay false before uplink write and downlink response")
	}

	writeBuf := buf.New()
	_, _ = writeBuf.Write([]byte("hello"))
	go func() {
		tmp := make([]byte, 16)
		_, _ = c2.Read(tmp)
		_, _ = c2.Write([]byte("world"))
	}()
	if err := pConn.WriteBuffer(writeBuf); err != nil {
		t.Fatalf("WriteBuffer failed: %v", err)
	}
	if pConn.WriterReplaceable() {
		t.Fatalf("WriterReplaceable must stay false before downlink response")
	}
	readBuf := buf.New()
	if err := pConn.ReadBuffer(readBuf); err != nil {
		t.Fatalf("ReadBuffer failed: %v", err)
	}
	if string(readBuf.Bytes()) != "world" {
		t.Fatalf("expected 'world', got %q", string(readBuf.Bytes()))
	}
	if !succeeded.Load() || !pConn.ReaderReplaceable() || !pConn.WriterReplaceable() {
		t.Fatalf("expected succeeded and both replaceable flags true after downlink response")
	}
}
