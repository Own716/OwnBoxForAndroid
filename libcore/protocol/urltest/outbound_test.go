package urltest

import (
	"context"
	"net"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	singUrltest "github.com/sagernet/sing-box/common/urltest"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

type testMockOutbound struct {
	outbound.Adapter
}

var _ adapter.Outbound = (*testMockOutbound)(nil)

func newTestOutbound(tag string) *testMockOutbound {
	return &testMockOutbound{
		Adapter: outbound.NewAdapter("mock", tag, []string{N.NetworkTCP, N.NetworkUDP}, nil),
	}
}

func (m *testMockOutbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	return nil, nil
}

func (m *testMockOutbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
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
