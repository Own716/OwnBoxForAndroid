package autoselect

import (
	"container/list"
	"io"
	"net"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/net/publicsuffix"
)

const (
	recentOutcomeWindow  = 10
	maxSiteMemoryEntries = 2048
	siteAvoidDuration    = 10 * time.Minute
)

type passiveNodeStats struct {
	recent           []bool
	consecutiveFails int
	ttfbEMA          float64
	lastFailAt       time.Time
}

type siteEntry struct {
	siteKey   string
	nodeTag   string
	updatedAt time.Time
}

type avoidKey struct {
	siteKey string
	nodeTag string
}

type passiveTracker struct {
	mu            sync.RWMutex
	siteStickyTTL time.Duration
	nowFn         func() time.Time

	nodes     map[string]*passiveNodeStats
	siteList  *list.List
	siteMap   map[string]*list.Element
	siteAvoid map[avoidKey]time.Time
}

func newPassiveTracker(siteStickyTTL time.Duration) *passiveTracker {
	return &passiveTracker{
		siteStickyTTL: siteStickyTTL,
		nowFn:         time.Now,
		nodes:         make(map[string]*passiveNodeStats),
		siteList:      list.New(),
		siteMap:       make(map[string]*list.Element),
		siteAvoid:     make(map[avoidKey]time.Time),
	}
}

func (p *passiveTracker) now() time.Time {
	if p != nil && p.nowFn != nil {
		return p.nowFn()
	}
	return time.Now()
}

func (p *passiveTracker) getNodeLocked(tag string) *passiveNodeStats {
	st, ok := p.nodes[tag]
	if !ok {
		st = &passiveNodeStats{}
		p.nodes[tag] = st
	}
	return st
}

func (p *passiveTracker) RecordSuccess(nodeTag, destHost string, ttfb time.Duration) {
	if p == nil || nodeTag == "" {
		return
	}
	now := p.now()
	siteKey := normalizeSiteKey(destHost)

	p.mu.Lock()
	defer p.mu.Unlock()

	st := p.getNodeLocked(nodeTag)
	st.recent = append(st.recent, true)
	if len(st.recent) > recentOutcomeWindow {
		st.recent = append([]bool(nil), st.recent[len(st.recent)-recentOutcomeWindow:]...)
	}
	st.consecutiveFails = 0

	if ttfb > 0 {
		ms := float64(ttfb) / float64(time.Millisecond)
		if ms < 1 {
			ms = 1
		}
		if st.ttfbEMA == 0 {
			st.ttfbEMA = ms
		} else {
			st.ttfbEMA = 0.3*ms + 0.7*st.ttfbEMA
		}
	}

	if siteKey != "" {
		delete(p.siteAvoid, avoidKey{siteKey: siteKey, nodeTag: nodeTag})
		if p.siteStickyTTL > 0 {
			p.rememberSiteLocked(siteKey, nodeTag, now)
		}
	}
}

func (p *passiveTracker) RecordFailure(nodeTag, destHost string) {
	if p == nil || nodeTag == "" {
		return
	}
	now := p.now()
	siteKey := normalizeSiteKey(destHost)

	p.mu.Lock()
	defer p.mu.Unlock()

	st := p.getNodeLocked(nodeTag)
	st.recent = append(st.recent, false)
	if len(st.recent) > recentOutcomeWindow {
		st.recent = append([]bool(nil), st.recent[len(st.recent)-recentOutcomeWindow:]...)
	}
	st.consecutiveFails++
	st.lastFailAt = now

	if siteKey != "" {
		p.siteAvoid[avoidKey{siteKey: siteKey, nodeTag: nodeTag}] = now.Add(siteAvoidDuration)
		if el, ok := p.siteMap[siteKey]; ok {
			entry := el.Value.(*siteEntry)
			if entry.nodeTag == nodeTag {
				p.siteList.Remove(el)
				delete(p.siteMap, siteKey)
			}
		}
	}
}

func (p *passiveTracker) LastFailureAt(nodeTag string) time.Time {
	if p == nil || nodeTag == "" {
		return time.Time{}
	}
	p.mu.RLock()
	defer p.mu.RUnlock()
	if st, ok := p.nodes[nodeTag]; ok {
		return st.lastFailAt
	}
	return time.Time{}
}

func (p *passiveTracker) NodeStats(nodeTag string, now time.Time) (ttfbMs float64, recentSuccesses int, consecutiveFails int) {
	if p == nil || nodeTag == "" {
		return 0, 0, 0
	}
	p.mu.RLock()
	defer p.mu.RUnlock()
	st, ok := p.nodes[nodeTag]
	if !ok {
		return 0, 0, 0
	}
	return st.ttfbEMA, countTrue(st.recent), st.consecutiveFails
}

func (p *passiveTracker) PreferredSiteNode(destHost string, now time.Time) string {
	if p == nil || p.siteStickyTTL <= 0 || destHost == "" {
		return ""
	}
	siteKey := normalizeSiteKey(destHost)
	if siteKey == "" {
		return ""
	}
	if now.IsZero() {
		now = p.now()
	}
	p.mu.RLock()
	defer p.mu.RUnlock()
	el, ok := p.siteMap[siteKey]
	if !ok {
		return ""
	}
	entry := el.Value.(*siteEntry)
	if now.Sub(entry.updatedAt) >= p.siteStickyTTL {
		return ""
	}
	return entry.nodeTag
}

func (p *passiveTracker) ShouldAvoidSite(nodeTag, destHost string, now time.Time) bool {
	if p == nil || nodeTag == "" || destHost == "" {
		return false
	}
	siteKey := normalizeSiteKey(destHost)
	if siteKey == "" {
		return false
	}
	if now.IsZero() {
		now = p.now()
	}
	p.mu.RLock()
	defer p.mu.RUnlock()
	until, ok := p.siteAvoid[avoidKey{siteKey: siteKey, nodeTag: nodeTag}]
	return ok && now.Before(until)
}

func (p *passiveTracker) LearnedSiteCount(now time.Time) int {
	if p == nil || p.siteStickyTTL <= 0 {
		return 0
	}
	if now.IsZero() {
		now = p.now()
	}
	p.mu.RLock()
	defer p.mu.RUnlock()
	cnt := 0
	for el := p.siteList.Front(); el != nil; el = el.Next() {
		entry := el.Value.(*siteEntry)
		if now.Sub(entry.updatedAt) < p.siteStickyTTL {
			cnt++
		}
	}
	return cnt
}

func (p *passiveTracker) ClearLearned() {
	if p == nil {
		return
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	p.nodes = make(map[string]*passiveNodeStats)
	p.siteList.Init()
	p.siteMap = make(map[string]*list.Element)
	p.siteAvoid = make(map[avoidKey]time.Time)
}

func (p *passiveTracker) rememberSiteLocked(siteKey, nodeTag string, now time.Time) {
	if el, ok := p.siteMap[siteKey]; ok {
		entry := el.Value.(*siteEntry)
		entry.nodeTag = nodeTag
		entry.updatedAt = now
		p.siteList.MoveToFront(el)
		return
	}
	entry := &siteEntry{
		siteKey:   siteKey,
		nodeTag:   nodeTag,
		updatedAt: now,
	}
	el := p.siteList.PushFront(entry)
	p.siteMap[siteKey] = el
	for p.siteList.Len() > maxSiteMemoryEntries {
		back := p.siteList.Back()
		if back == nil {
			break
		}
		old := back.Value.(*siteEntry)
		p.siteList.Remove(back)
		delete(p.siteMap, old.siteKey)
	}
}

func countTrue(vals []bool) int {
	c := 0
	for _, v := range vals {
		if v {
			c++
		}
	}
	return c
}

func normalizeSiteKey(raw string) string {
	host := strings.TrimSpace(raw)
	if host == "" {
		return ""
	}
	if h, _, err := net.SplitHostPort(host); err == nil {
		host = h
	}
	host = strings.TrimPrefix(host, "[")
	host = strings.TrimSuffix(host, "]")
	host = strings.TrimSuffix(host, ".")
	host = strings.ToLower(host)
	if host == "" {
		return ""
	}
	if ip, err := netip.ParseAddr(host); err == nil {
		ip = ip.Unmap()
		if ip.Is4() {
			return netip.PrefixFrom(ip, 24).Masked().String()
		}
		if ip.Is6() {
			return netip.PrefixFrom(ip, 48).Masked().String()
		}
		return ip.String()
	}
	if root, err := publicsuffix.EffectiveTLDPlusOne(host); err == nil && root != "" {
		return root
	}
	return host
}

// passiveConn intercepts initial Write/Read traffic to capture TTFB and unanswered failures,
// and releases zero-copy fast path once a response has arrived.
type passiveConn struct {
	conn         net.Conn
	onSuccess    func(ttfb time.Duration)
	onFail       func()
	nowFn        func() time.Time
	written      atomic.Bool
	responded    atomic.Bool
	closed       atomic.Bool
	reported     atomic.Bool
	firstWriteNs atomic.Int64
}

func newPassiveConn(conn net.Conn, onSuccess func(time.Duration), onFail func(), nowFn func() time.Time) *passiveConn {
	if nowFn == nil {
		nowFn = time.Now
	}
	return &passiveConn{
		conn:      conn,
		onSuccess: onSuccess,
		onFail:    onFail,
		nowFn:     nowFn,
	}
}

func (c *passiveConn) Read(b []byte) (int, error) {
	n, err := c.conn.Read(b)
	if n > 0 && c.responded.CompareAndSwap(false, true) {
		var ttfb time.Duration
		if fw := c.firstWriteNs.Load(); fw > 0 {
			ttfb = time.Duration(c.nowFn().UnixNano() - fw)
			if ttfb < 0 {
				ttfb = 0
			}
		}
		if c.reported.CompareAndSwap(false, true) && c.onSuccess != nil {
			c.onSuccess(ttfb)
		}
	} else if err != nil && c.written.Load() && !c.responded.Load() {
		if err != io.EOF || n == 0 {
			c.reportFailureOnce()
		}
	}
	return n, err
}

func (c *passiveConn) Write(b []byte) (int, error) {
	if len(b) > 0 && c.written.CompareAndSwap(false, true) {
		c.firstWriteNs.CompareAndSwap(0, c.nowFn().UnixNano())
	}
	return c.conn.Write(b)
}

func (c *passiveConn) Close() error {
	c.closed.Store(true)
	if c.written.Load() && !c.responded.Load() {
		c.reportFailureOnce()
	}
	return c.conn.Close()
}

func (c *passiveConn) reportFailureOnce() {
	if c.reported.CompareAndSwap(false, true) && c.onFail != nil {
		c.onFail()
	}
}

func (c *passiveConn) LocalAddr() net.Addr                { return c.conn.LocalAddr() }
func (c *passiveConn) RemoteAddr() net.Addr               { return c.conn.RemoteAddr() }
func (c *passiveConn) SetDeadline(t time.Time) error      { return c.conn.SetDeadline(t) }
func (c *passiveConn) SetReadDeadline(t time.Time) error  { return c.conn.SetReadDeadline(t) }
func (c *passiveConn) SetWriteDeadline(t time.Time) error { return c.conn.SetWriteDeadline(t) }
func (c *passiveConn) Upstream() any                      { return c.conn }
func (c *passiveConn) ReaderReplaceable() bool            { return c.responded.Load() }
func (c *passiveConn) WriterReplaceable() bool            { return c.written.Load() && c.responded.Load() }

// passivePacketConn mirrors passiveConn monitoring for UDP packet connections.
type passivePacketConn struct {
	net.PacketConn
	onSuccess    func(ttfb time.Duration)
	onFail       func()
	nowFn        func() time.Time
	written      atomic.Bool
	responded    atomic.Bool
	reported     atomic.Bool
	firstWriteNs atomic.Int64
}

func newPassivePacketConn(conn net.PacketConn, onSuccess func(time.Duration), onFail func(), nowFn func() time.Time) *passivePacketConn {
	if nowFn == nil {
		nowFn = time.Now
	}
	return &passivePacketConn{
		PacketConn: conn,
		onSuccess:  onSuccess,
		onFail:     onFail,
		nowFn:      nowFn,
	}
}

func (c *passivePacketConn) ReadFrom(p []byte) (int, net.Addr, error) {
	n, addr, err := c.PacketConn.ReadFrom(p)
	if n > 0 && c.responded.CompareAndSwap(false, true) {
		var ttfb time.Duration
		if fw := c.firstWriteNs.Load(); fw > 0 {
			ttfb = time.Duration(c.nowFn().UnixNano() - fw)
			if ttfb < 0 {
				ttfb = 0
			}
		}
		if c.reported.CompareAndSwap(false, true) && c.onSuccess != nil {
			c.onSuccess(ttfb)
		}
	}
	return n, addr, err
}

func (c *passivePacketConn) WriteTo(p []byte, addr net.Addr) (int, error) {
	if len(p) > 0 && c.written.CompareAndSwap(false, true) {
		c.firstWriteNs.CompareAndSwap(0, c.nowFn().UnixNano())
	}
	return c.PacketConn.WriteTo(p, addr)
}

func (c *passivePacketConn) Close() error {
	if c.written.Load() && !c.responded.Load() && c.reported.CompareAndSwap(false, true) && c.onFail != nil {
		c.onFail()
	}
	return c.PacketConn.Close()
}

func (c *passivePacketConn) Upstream() any           { return c.PacketConn }
func (c *passivePacketConn) ReaderReplaceable() bool { return c.responded.Load() }
func (c *passivePacketConn) WriterReplaceable() bool { return c.written.Load() && c.responded.Load() }
