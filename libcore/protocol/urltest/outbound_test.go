package urltest

import (
	"context"
	"errors"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/interrupt"
	singUrltest "github.com/sagernet/sing-box/common/urltest"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

type testMockOutbound struct {
	outbound.Adapter
	dialFn   func(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error)
	packetFn func(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error)
}

var _ adapter.Outbound = (*testMockOutbound)(nil)

func newTestOutbound(tag string) *testMockOutbound {
	return &testMockOutbound{
		Adapter: outbound.NewAdapter("mock", tag, []string{N.NetworkTCP, N.NetworkUDP}, nil),
	}
}

func (m *testMockOutbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	if m.dialFn != nil {
		return m.dialFn(ctx, network, destination)
	}
	return nil, nil
}

func (m *testMockOutbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	if m.packetFn != nil {
		return m.packetFn(ctx, destination)
	}
	return nil, nil
}

func createTestURLTestGroup(tags []string, tolerance uint16) (*URLTestGroup, *singUrltest.HistoryStorage, []*testMockOutbound) {
	history := singUrltest.NewHistoryStorage()
	outbounds := make([]adapter.Outbound, len(tags))
	mockList := make([]*testMockOutbound, len(tags))
	for i, tag := range tags {
		ob := newTestOutbound(tag)
		outbounds[i] = ob
		mockList[i] = ob
	}
	grp := &URLTestGroup{
		ctx:       context.Background(),
		outbounds: outbounds,
		history:   history,
		tolerance: tolerance,
	}
	return grp, history, mockList
}

// 测试 1：A=300ms, B=100ms, C=150ms, D=80ms -> 必须选中 D (80ms)
func TestLeastPingSelectionMultiNode(t *testing.T) {
	tags := []string{"node-A", "node-B", "node-C", "node-D"}
	grp, history, _ := createTestURLTestGroup(tags, 50)

	history.StoreURLTestHistory("node-A", &adapter.URLTestHistory{Time: time.Now(), Delay: 300})
	history.StoreURLTestHistory("node-B", &adapter.URLTestHistory{Time: time.Now(), Delay: 100})
	history.StoreURLTestHistory("node-C", &adapter.URLTestHistory{Time: time.Now(), Delay: 150})
	history.StoreURLTestHistory("node-D", &adapter.URLTestHistory{Time: time.Now(), Delay: 80})

	selected, exists := grp.Select(N.NetworkTCP)
	if !exists {
		t.Fatal("expected exists to be true")
	}
	if selected == nil || selected.Tag() != "node-D" {
		t.Fatalf("expected node-D (80ms) to be selected, got %v", selected)
	}
}

// 测试 2：A=80ms, B=150ms, C=100ms, D=200ms -> 必须选中 A (80ms)
func TestLeastPingSelectionFirstNodeBest(t *testing.T) {
	tags := []string{"node-A", "node-B", "node-C", "node-D"}
	grp, history, _ := createTestURLTestGroup(tags, 50)

	history.StoreURLTestHistory("node-A", &adapter.URLTestHistory{Time: time.Now(), Delay: 80})
	history.StoreURLTestHistory("node-B", &adapter.URLTestHistory{Time: time.Now(), Delay: 150})
	history.StoreURLTestHistory("node-C", &adapter.URLTestHistory{Time: time.Now(), Delay: 100})
	history.StoreURLTestHistory("node-D", &adapter.URLTestHistory{Time: time.Now(), Delay: 200})

	selected, exists := grp.Select(N.NetworkTCP)
	if !exists {
		t.Fatal("expected exists to be true")
	}
	if selected == nil || selected.Tag() != "node-A" {
		t.Fatalf("expected node-A (80ms) to be selected, got %v", selected)
	}
}

// 测试 3：第一个节点没有测速结果：A=unknown, B=100ms, C=150ms -> 必须选中 B (100ms)，不能因 A 在首位就选 A
func TestLeastPingSelectionFirstNodeUntested(t *testing.T) {
	tags := []string{"node-A", "node-B", "node-C"}
	grp, history, _ := createTestURLTestGroup(tags, 50)

	// node-A 没有写入 history (unknown)
	history.StoreURLTestHistory("node-B", &adapter.URLTestHistory{Time: time.Now(), Delay: 100})
	history.StoreURLTestHistory("node-C", &adapter.URLTestHistory{Time: time.Now(), Delay: 150})

	selected, exists := grp.Select(N.NetworkTCP)
	if !exists {
		t.Fatal("expected exists to be true")
	}
	if selected == nil || selected.Tag() != "node-B" {
		t.Fatalf("expected node-B (100ms) to be selected over untested node-A, got %v", selected)
	}
}

// 测试 4：全部节点没有测速：A=unknown, B=unknown, C=unknown -> 允许临时 fallback，测速完成后必须重新选择
func TestLeastPingSelectionAllUntestedFallbackAndReevaluation(t *testing.T) {
	tags := []string{"node-A", "node-B", "node-C"}
	grp, history, mocks := createTestURLTestGroup(tags, 50)

	// 1. 全部无测速：临时 fallback 至首个节点，exists 为 false
	selected, exists := grp.Select(N.NetworkTCP)
	if exists {
		t.Fatal("expected exists to be false when no history exists")
	}
	if selected == nil || selected.Tag() != "node-A" {
		t.Fatalf("expected provisional fallback to node-A, got %v", selected)
	}

	// 模拟启动阶段记录 provisional 节点
	grp.selectedOutboundTCP = mocks[0]

	// 2. 测速完成并写入结果：node-B 为最优 (60ms)
	history.StoreURLTestHistory("node-A", &adapter.URLTestHistory{Time: time.Now(), Delay: 250})
	history.StoreURLTestHistory("node-B", &adapter.URLTestHistory{Time: time.Now(), Delay: 60})
	history.StoreURLTestHistory("node-C", &adapter.URLTestHistory{Time: time.Now(), Delay: 180})

	// 触发 performUpdateCheck
	grp.performUpdateCheck()

	if grp.selectedOutboundTCP == nil || grp.selectedOutboundTCP.Tag() != "node-B" {
		t.Fatalf("expected re-evaluation after test to upgrade to node-B (60ms), got %v", grp.selectedOutboundTCP)
	}
}

// 测试 5：当前 A=80ms, B=100ms。后来 A 升至 160ms，B=100ms -> 超出 tolerance (50ms) 触发切换至 B
func TestLeastPingToleranceHysteresisSwitch(t *testing.T) {
	tags := []string{"node-A", "node-B"}
	grp, history, mocks := createTestURLTestGroup(tags, 50)

	history.StoreURLTestHistory("node-A", &adapter.URLTestHistory{Time: time.Now(), Delay: 80})
	history.StoreURLTestHistory("node-B", &adapter.URLTestHistory{Time: time.Now(), Delay: 100})

	grp.selectedOutboundTCP = mocks[0] // 当前选中 A (80ms)

	// A 升至 160ms，比 B (100ms) 慢 60ms (> 50ms tolerance)
	history.StoreURLTestHistory("node-A", &adapter.URLTestHistory{Time: time.Now(), Delay: 160})

	selected, exists := grp.Select(N.NetworkTCP)
	if !exists {
		t.Fatal("expected exists to be true")
	}
	if selected == nil || selected.Tag() != "node-B" {
		t.Fatalf("expected switch to node-B because delta (60ms) > tolerance (50ms), got %v", selected)
	}
}

// 测试 6：当前 A=80ms, B=82ms。A 微抖至 85ms -> delta (3ms) < tolerance (50ms) 不发生频繁切换
func TestLeastPingToleranceJitterNoSwitch(t *testing.T) {
	tags := []string{"node-A", "node-B"}
	grp, history, mocks := createTestURLTestGroup(tags, 50)

	history.StoreURLTestHistory("node-A", &adapter.URLTestHistory{Time: time.Now(), Delay: 80})
	history.StoreURLTestHistory("node-B", &adapter.URLTestHistory{Time: time.Now(), Delay: 82})

	grp.selectedOutboundTCP = mocks[0] // 当前活跃连接在 A

	// A 发生轻微抖动至 85ms，此时 B (82ms) 比 A (85ms) 仅快 3ms (< 50ms tolerance)
	history.StoreURLTestHistory("node-A", &adapter.URLTestHistory{Time: time.Now(), Delay: 85})

	selected, exists := grp.Select(N.NetworkTCP)
	if !exists {
		t.Fatal("expected exists to be true")
	}
	if selected == nil || selected.Tag() != "node-A" {
		t.Fatalf("expected to remain on node-A to prevent flapping on small jitter, got %v", selected)
	}
}

// 测试 7：当前节点 A 失败（加惩罚延迟 300ms），A=380ms, B=100ms -> 自动避开 A 切换至 B
func TestLeastPingNodeFailureAvoidance(t *testing.T) {
	tags := []string{"node-A", "node-B", "node-C"}
	grp, history, mocks := createTestURLTestGroup(tags, 50)

	history.StoreURLTestHistory("node-A", &adapter.URLTestHistory{Time: time.Now(), Delay: 80})
	history.StoreURLTestHistory("node-B", &adapter.URLTestHistory{Time: time.Now(), Delay: 100})
	history.StoreURLTestHistory("node-C", &adapter.URLTestHistory{Time: time.Now(), Delay: 120})

	grp.selectedOutboundTCP = mocks[0] // 当前在 A

	// 模拟 A 单次超时被记录惩罚延迟 80 + 300 = 380ms
	history.StoreURLTestHistory("node-A", &adapter.URLTestHistory{Time: time.Now(), Delay: 380})

	selected, exists := grp.Select(N.NetworkTCP)
	if !exists {
		t.Fatal("expected exists to be true")
	}
	if selected == nil || selected.Tag() != "node-B" {
		t.Fatalf("expected avoidance of degraded node-A, switching to node-B (100ms), got %v", selected)
	}
}

// 测试 8：A 恢复：A=70ms, B=100ms -> A 重新成为最优并被选中
func TestLeastPingNodeRecovery(t *testing.T) {
	tags := []string{"node-A", "node-B"}
	grp, history, mocks := createTestURLTestGroup(tags, 50)

	history.StoreURLTestHistory("node-A", &adapter.URLTestHistory{Time: time.Now(), Delay: 380}) // 之前失败
	history.StoreURLTestHistory("node-B", &adapter.URLTestHistory{Time: time.Now(), Delay: 100})
	grp.selectedOutboundTCP = mocks[1] // 当前已切至 B

	// A 恢复并测出 70ms：100 > 70 + 20，差值 30ms。若差值超过 tolerance (20ms)，顺利重选 A
	grp.tolerance = 20
	history.StoreURLTestHistory("node-A", &adapter.URLTestHistory{Time: time.Now(), Delay: 70})

	selected, exists := grp.Select(N.NetworkTCP)
	if !exists {
		t.Fatal("expected exists to be true")
	}
	if selected == nil || selected.Tag() != "node-A" {
		t.Fatalf("expected node-A to reclaim selection after recovery, got %v", selected)
	}
}

// 测试 9：订阅更新：原 [A, B, C]，更新后 [D, A, C]；A 历史保留，B 移除，D 未测速 -> 选中 A
func TestLeastPingSubscriptionUpdateAndReorder(t *testing.T) {
	// 共享同一 HistoryStorage
	history := singUrltest.NewHistoryStorage()
	history.StoreURLTestHistory("node-A", &adapter.URLTestHistory{Time: time.Now(), Delay: 100})
	history.StoreURLTestHistory("node-B", &adapter.URLTestHistory{Time: time.Now(), Delay: 80})
	history.StoreURLTestHistory("node-C", &adapter.URLTestHistory{Time: time.Now(), Delay: 120})

	// 模拟订阅更新：B 被删除，加入新节点 D（未测速），顺序重排为 [D, A, C]
	newTags := []string{"node-D", "node-A", "node-C"}
	outbounds := make([]adapter.Outbound, len(newTags))
	for i, tag := range newTags {
		outbounds[i] = newTestOutbound(tag)
	}
	newGrp := &URLTestGroup{
		ctx:       context.Background(),
		outbounds: outbounds,
		history:   history,
		tolerance: 50,
	}

	selected, exists := newGrp.Select(N.NetworkTCP)
	if !exists {
		t.Fatal("expected exists to be true")
	}
	if selected == nil || selected.Tag() != "node-A" {
		t.Fatalf("expected node-A (100ms) to be selected in reordered list [D, A, C], got %v", selected)
	}
}

// 测试 10：策略组隔离：策略组 1 与 策略组 2 分别选出各自的最优节点，互不干扰
func TestLeastPingStrategyGroupIsolation(t *testing.T) {
	grp1, hist1, _ := createTestURLTestGroup([]string{"hk-01", "hk-02"}, 50)
	grp2, hist2, _ := createTestURLTestGroup([]string{"jp-01", "jp-02"}, 50)

	hist1.StoreURLTestHistory("hk-01", &adapter.URLTestHistory{Time: time.Now(), Delay: 60})
	hist1.StoreURLTestHistory("hk-02", &adapter.URLTestHistory{Time: time.Now(), Delay: 120})

	hist2.StoreURLTestHistory("jp-01", &adapter.URLTestHistory{Time: time.Now(), Delay: 180})
	hist2.StoreURLTestHistory("jp-02", &adapter.URLTestHistory{Time: time.Now(), Delay: 75})

	sel1, _ := grp1.Select(N.NetworkTCP)
	sel2, _ := grp2.Select(N.NetworkTCP)

	if sel1 == nil || sel1.Tag() != "hk-01" {
		t.Fatalf("expected grp1 to select hk-01, got %v", sel1)
	}
	if sel2 == nil || sel2.Tag() != "jp-02" {
		t.Fatalf("expected grp2 to select jp-02, got %v", sel2)
	}
}

type stubConn struct {
	closed atomic.Bool
}

func (c *stubConn) Read(b []byte) (int, error)         { return 0, nil }
func (c *stubConn) Write(b []byte) (int, error)        { return len(b), nil }
func (c *stubConn) Close() error                       { c.closed.Store(true); return nil }
func (c *stubConn) LocalAddr() net.Addr                { return nil }
func (c *stubConn) RemoteAddr() net.Addr               { return nil }
func (c *stubConn) SetDeadline(t time.Time) error      { return nil }
func (c *stubConn) SetReadDeadline(t time.Time) error  { return nil }
func (c *stubConn) SetWriteDeadline(t time.Time) error { return nil }

type stubPacketConn struct {
	closed atomic.Bool
}

func (p *stubPacketConn) ReadFrom(b []byte) (int, net.Addr, error) { return 0, nil, nil }
func (p *stubPacketConn) WriteTo(b []byte, addr net.Addr) (int, error) {
	return len(b), nil
}
func (p *stubPacketConn) Close() error                       { p.closed.Store(true); return nil }
func (p *stubPacketConn) LocalAddr() net.Addr                { return nil }
func (p *stubPacketConn) SetDeadline(t time.Time) error      { return nil }
func (p *stubPacketConn) SetReadDeadline(t time.Time) error  { return nil }
func (p *stubPacketConn) SetWriteDeadline(t time.Time) error { return nil }

// 测试 11：容灾按最低延迟排序、防抖阈值切换、且绝不打断已建立的外部连接
func TestDialFailoverPrefersLowestDelayAndAntiFlapPromotion(t *testing.T) {
	tags := []string{"primary", "slow-backup", "fast-backup"}
	grp, history, mocks := createTestURLTestGroup(tags, 50)
	grp.interval = 3 * time.Minute
	grp.interruptGroup = interrupt.NewGroup()
	grp.interruptExternalConnections = true

	history.StoreURLTestHistory("primary", &adapter.URLTestHistory{Time: time.Now(), Delay: 50})
	history.StoreURLTestHistory("slow-backup", &adapter.URLTestHistory{Time: time.Now(), Delay: 300})
	history.StoreURLTestHistory("fast-backup", &adapter.URLTestHistory{Time: time.Now(), Delay: 70})
	grp.selectedOutboundTCP = mocks[0]

	var (
		mu          sync.Mutex
		dialAttempt []string
	)
	recordAttempt := func(tag string) {
		mu.Lock()
		dialAttempt = append(dialAttempt, tag)
		mu.Unlock()
	}

	mocks[0].dialFn = func(ctx context.Context, network string, dest M.Socksaddr) (net.Conn, error) {
		recordAttempt("primary")
		return nil, errors.New("primary connection reset")
	}
	mocks[1].dialFn = func(ctx context.Context, network string, dest M.Socksaddr) (net.Conn, error) {
		recordAttempt("slow-backup")
		return &stubConn{}, nil
	}
	fastConn1 := &stubConn{}
	var fastCount atomic.Int32
	mocks[2].dialFn = func(ctx context.Context, network string, dest M.Socksaddr) (net.Conn, error) {
		recordAttempt("fast-backup")
		if fastCount.Add(1) == 1 {
			return fastConn1, nil
		}
		return &stubConn{}, nil
	}

	u := &URLTest{group: grp}
	dest := M.ParseSocksaddrHostPort("example.com", 443)

	// 首次单点失败：立即容灾至延迟更低的 fast-backup（跳过 config 在前的 slow-backup），但暂不改主选防单次抖动横跳
	extCtx := interrupt.ContextWithIsExternalConnection(context.Background(), true)
	conn1, err := u.DialContext(extCtx, N.NetworkTCP, dest)
	if err != nil {
		t.Fatalf("expected failover connection 1 to succeed, got %v", err)
	}
	if u.Now() != "primary" {
		t.Fatalf("expected primary to remain selected after single transient failure, got %s", u.Now())
	}

	// 第二次失败：达到阈值，自动晋升为 fast-backup，且之前建连的 fastConn1 不被掐断
	conn2, err := u.DialContext(extCtx, N.NetworkTCP, dest)
	if err != nil {
		t.Fatalf("expected failover connection 2 to succeed, got %v", err)
	}
	_ = conn2
	if u.Now() != "fast-backup" {
		t.Fatalf("expected promotion to fast-backup after threshold failures, got %s", u.Now())
	}
	if fastConn1.closed.Load() {
		t.Fatal("existing healthy connection must NOT be interrupted during failover promotion")
	}
	_ = conn1

	// 第三次请求：直接命中 fast-backup，不再去撞失败的 primary
	dialAttempt = nil
	if _, err := u.DialContext(context.Background(), N.NetworkTCP, dest); err != nil {
		t.Fatalf("expected third dial to succeed via promoted fast-backup, got %v", err)
	}
	if len(dialAttempt) != 1 || dialAttempt[0] != "fast-backup" {
		t.Fatalf("expected direct dial to fast-backup only, got %v", dialAttempt)
	}

	// 验证原主节点测速缓存已被惩罚并过期，以便下轮探测重测而不回弹
	penalized := history.LoadURLTestHistory("primary")
	if penalized == nil || penalized.Delay < 1000 {
		t.Fatalf("expected primary delay to be penalized after confirmed failover, got %+v", penalized)
	}
	if time.Since(penalized.Time) < grp.interval {
		t.Fatalf("expected penalized primary history timestamp to be aged for re-probe, got %v", penalized.Time)
	}
}

// 测试 12：调用方 Context 已取消时，不盲目轮询备用节点且不惩罚当前主选节点
func TestDialFailoverCanceledContextSkipsBackupAndPenalty(t *testing.T) {
	tags := []string{"primary", "backup"}
	grp, history, mocks := createTestURLTestGroup(tags, 50)
	grp.interruptGroup = interrupt.NewGroup()
	grp.selectedOutboundTCP = mocks[0]
	history.StoreURLTestHistory("primary", &adapter.URLTestHistory{Time: time.Now(), Delay: 50})
	history.StoreURLTestHistory("backup", &adapter.URLTestHistory{Time: time.Now(), Delay: 80})

	ctx, cancel := context.WithCancel(context.Background())
	mocks[0].dialFn = func(c context.Context, network string, dest M.Socksaddr) (net.Conn, error) {
		cancel()
		return nil, context.Canceled
	}
	var backupCalled atomic.Bool
	mocks[1].dialFn = func(c context.Context, network string, dest M.Socksaddr) (net.Conn, error) {
		backupCalled.Store(true)
		return &stubConn{}, nil
	}

	u := &URLTest{group: grp}
	_, err := u.DialContext(ctx, N.NetworkTCP, M.ParseSocksaddrHostPort("example.com", 443))
	if err == nil {
		t.Fatal("expected error when caller context is canceled")
	}
	if backupCalled.Load() {
		t.Fatal("backup outbound should not be dialed after context cancellation")
	}
	if u.Now() != "primary" {
		t.Fatalf("expected primary to remain selected on context cancel, got %s", u.Now())
	}
}

// 测试 13：UDP ListenPacket 容灾晋升与并发读写竞态验证
func TestListenPacketFailoverAndConcurrentAccess(t *testing.T) {
	tags := []string{"udp-a", "udp-dead-backup", "udp-b"}
	grp, history, mocks := createTestURLTestGroup(tags, 50)
	grp.interruptGroup = interrupt.NewGroup()
	grp.selectedOutboundUDP = mocks[0]
	grp.selectedOutboundTCP = mocks[0]

	history.StoreURLTestHistory("udp-a", &adapter.URLTestHistory{Time: time.Now(), Delay: 40})
	history.StoreURLTestHistory("udp-dead-backup", &adapter.URLTestHistory{Time: time.Now(), Delay: 60})
	history.StoreURLTestHistory("udp-b", &adapter.URLTestHistory{Time: time.Now(), Delay: 70})

	mocks[0].packetFn = func(ctx context.Context, dest M.Socksaddr) (net.PacketConn, error) {
		return nil, errors.New("udp primary failed")
	}
	mocks[1].packetFn = func(ctx context.Context, dest M.Socksaddr) (net.PacketConn, error) {
		return nil, errors.New("udp backup 1 also failed")
	}
	mocks[2].packetFn = func(ctx context.Context, dest M.Socksaddr) (net.PacketConn, error) {
		return &stubPacketConn{}, nil
	}

	u := &URLTest{group: grp}
	dest := M.ParseSocksaddrHostPort("1.1.1.1", 53)
	for i := 0; i < dialFailThreshold; i++ {
		if _, err := u.ListenPacket(context.Background(), dest); err != nil {
			t.Fatalf("ListenPacket attempt %d failed: %v", i+1, err)
		}
	}
	if sel := u.Selected(N.NetworkUDP); sel == nil || sel.Tag() != "udp-b" {
		t.Fatalf("expected UDP failover promotion to udp-b, got %v", sel)
	}

	// 并发读写检查：performUpdateCheck + DialContext + Selected + Now 无数据竞争
	for i := range mocks {
		mocks[i].dialFn = func(ctx context.Context, network string, d M.Socksaddr) (net.Conn, error) {
			return &stubConn{}, nil
		}
	}
	var wg sync.WaitGroup
	for g := 0; g < 8; g++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			for step := 0; step < 50; step++ {
				if id%2 == 0 {
					grp.performUpdateCheck()
				} else {
					_ = u.Now()
					_ = u.Selected(N.NetworkTCP)
					if c, err := u.DialContext(context.Background(), N.NetworkTCP, dest); err == nil && c != nil {
						_ = c.Close()
					}
				}
			}
		}(g)
	}
	wg.Wait()
}
