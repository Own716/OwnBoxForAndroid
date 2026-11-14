package urltest

import (
	"bufio"
	"context"
	"errors"
	"net"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

func serveHTTP204Pipe(delay time.Duration) (net.Conn, error) {
	if delay > 0 {
		time.Sleep(delay)
	}
	client, server := net.Pipe()
	go func() {
		defer server.Close()
		reader := bufio.NewReader(server)
		_, _ = http.ReadRequest(reader)
		_, _ = server.Write([]byte("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"))
	}()
	return client, nil
}

func TestURLTestPinStrictHoldAndUnpin(t *testing.T) {
	tags := []string{"node-A", "node-B", "node-C"}
	grp, history, mocks := createTestURLTestGroup(tags, 50)
	history.StoreURLTestHistory("node-A", &adapter.URLTestHistory{Time: time.Now(), Delay: 40})
	history.StoreURLTestHistory("node-B", &adapter.URLTestHistory{Time: time.Now(), Delay: 200})
	history.StoreURLTestHistory("node-C", &adapter.URLTestHistory{Time: time.Now(), Delay: 80})

	u := &URLTest{
		Adapter: outbound.NewAdapter("urltest", "proxy", []string{N.NetworkTCP, N.NetworkUDP}, tags),
		ctx:     context.Background(),
		tags:    tags,
		group:   grp,
	}

	var backupDialCount atomic.Int32
	mocks[0].dialFn = func(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
		backupDialCount.Add(1)
		c1, c2 := net.Pipe()
		_ = c2.Close()
		return c1, nil
	}
	mocks[2].dialFn = mocks[0].dialFn
	mocks[0].packetFn = func(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
		backupDialCount.Add(1)
		return nil, nil
	}
	mocks[2].packetFn = mocks[0].packetFn

	dialErr := errors.New("pinned node broken")
	mocks[1].dialFn = func(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
		return nil, dialErr
	}
	mocks[1].packetFn = func(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
		return nil, dialErr
	}

	if u.PinOutbound("nonexistent") {
		t.Fatal("expected PinOutbound(nonexistent) to return false")
	}
	if !u.PinOutbound("node-B") {
		t.Fatal("expected PinOutbound(node-B) to return true")
	}

	snap := u.GroupSnapshot()
	if snap.GroupTag != "proxy" || snap.StrategyType != "leastPing" {
		t.Fatalf("unexpected snapshot metadata: %+v", snap)
	}
	if snap.PinnedTag != "node-B" || snap.ActiveTag != "node-B" {
		t.Fatalf("expected active & pinned to be node-B, got active=%q pinned=%q", snap.ActiveTag, snap.PinnedTag)
	}

	// Update history to verify performUpdateCheck does not override pinned selection.
	history.StoreURLTestHistory("node-A", &adapter.URLTestHistory{Time: time.Now(), Delay: 20})
	grp.performUpdateCheck()
	if u.PinnedOutbound() != "node-B" || u.Now() != "node-B" {
		t.Fatalf("expected pin to survive performUpdateCheck, got pinned=%q now=%q", u.PinnedOutbound(), u.Now())
	}

	// Strict hold on TCP failure: must fail without attempting node-A or node-C.
	dest := M.ParseSocksaddrHostPort("example.com", 443)
	if _, err := u.DialContext(context.Background(), N.NetworkTCP, dest); !errors.Is(err, dialErr) {
		t.Fatalf("expected TCP dialErr, got %v", err)
	}
	if _, err := u.ListenPacket(context.Background(), dest); !errors.Is(err, dialErr) {
		t.Fatalf("expected UDP dialErr, got %v", err)
	}
	if backupDialCount.Load() != 0 {
		t.Fatalf("strict pin must not fail over to backups, got %d backup dials", backupDialCount.Load())
	}

	// Restoring auto clears pin and picks the fastest healthy outbound (node-A).
	if !u.PinOutbound("") {
		t.Fatal("expected PinOutbound(\"\") to return true")
	}
	if u.PinnedOutbound() != "" {
		t.Fatalf("expected cleared pin, got %q", u.PinnedOutbound())
	}
	if u.Now() != "node-A" {
		t.Fatalf("expected auto restoration to select node-A, got %q", u.Now())
	}
	conn, err := u.DialContext(context.Background(), N.NetworkTCP, dest)
	if err != nil {
		t.Fatalf("expected auto dial after unpin to succeed, got %v", err)
	}
	_ = conn.Close()
}

func TestURLTestCheckAndSnapshotRecordsErrorAndDelay(t *testing.T) {
	tags := []string{"ok-node", "dead-node"}
	grp, history, mocks := createTestURLTestGroup(tags, 50)
	// Seed prior cached history for dead-node so penalty retention still keeps old delay in history.
	history.StoreURLTestHistory("dead-node", &adapter.URLTestHistory{Time: time.Now(), Delay: 120})
	grp.link = "http://127.0.0.1/generate_204"
	grp.interval = 5 * time.Minute

	mocks[0].dialFn = func(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
		return serveHTTP204Pipe(8 * time.Millisecond)
	}
	mocks[1].dialFn = func(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
		return nil, errors.New("EOF")
	}

	u := &URLTest{
		Adapter: outbound.NewAdapter("urltest", "proxy", []string{N.NetworkTCP, N.NetworkUDP}, tags),
		ctx:     context.Background(),
		tags:    tags,
		link:    grp.link,
		group:   grp,
	}

	snap := u.CheckAndSnapshot(context.Background())
	if snap.LastCheckMs <= 0 {
		t.Fatalf("expected positive LastCheckMs, got %d", snap.LastCheckMs)
	}
	if len(snap.Members) != 2 {
		t.Fatalf("expected 2 members, got %d", len(snap.Members))
	}

	byTag := make(map[string]MemberSnapshot, len(snap.Members))
	for _, m := range snap.Members {
		byTag[m.Tag] = m
	}

	okSnap := byTag["ok-node"]
	if okSnap.Error != "" || okSnap.Delay <= 0 || okSnap.LastCheckMs <= 0 {
		t.Fatalf("expected healthy ok-node snapshot, got %+v", okSnap)
	}
	deadSnap := byTag["dead-node"]
	if !strings.Contains(deadSnap.Error, "EOF") || deadSnap.Delay != 0 || deadSnap.LastCheckMs <= 0 {
		t.Fatalf("expected error+0 delay in snapshot even with retained history, got %+v", deadSnap)
	}
}
