package autoselect

import (
	"net"
	"testing"
	"time"
)

func TestPassiveConnReplaceableAndUnansweredCloseFailure(t *testing.T) {
	now := time.Unix(1700000000, 0)
	tracker := newPassiveTracker(60 * time.Minute)
	tracker.nowFn = func() time.Time { return now }

	// Case 1: Write sent, zero response bytes read, Close -> unanswered passive fail!
	client1, server1 := net.Pipe()
	wrapped1 := newPassiveConn(client1, func(ttfb time.Duration) {
		tracker.RecordSuccess("node-a", "", ttfb)
	}, func() {
		tracker.RecordFailure("node-a", "example.com")
	}, func() time.Time { return now })

	if wrapped1.ReaderReplaceable() || wrapped1.WriterReplaceable() {
		t.Fatalf("passiveConn MUST NOT be replaceable before write+response are observed")
	}

	go func() {
		buf := make([]byte, 64)
		_, _ = server1.Read(buf)
		_ = server1.Close()
	}()

	_, err := wrapped1.Write([]byte("GET / HTTP/1.1\r\n\r\n"))
	if err != nil {
		t.Fatalf("unexpected write error: %v", err)
	}
	if wrapped1.WriterReplaceable() {
		t.Fatalf("WriterReplaceable must remain false until downlink response arrives")
	}
	tmp := make([]byte, 16)
	_, _ = wrapped1.Read(tmp) // EOF
	_ = wrapped1.Close()

	_, _, consecutiveFails := tracker.NodeStats("node-a", now)
	if consecutiveFails != 1 {
		t.Fatalf("expected 1 unanswered passive failure, got %d", consecutiveFails)
	}

	// Case 2: Idle cancel before ANY Write -> MUST NOT count as passive failure!
	client2, server2 := net.Pipe()
	wrapped2 := newPassiveConn(client2, func(ttfb time.Duration) {
		tracker.RecordSuccess("node-a", "", ttfb)
	}, func() {
		tracker.RecordFailure("node-a", "example.com")
	}, func() time.Time { return now })
	_ = wrapped2.Close()
	_ = server2.Close()

	_, _, consecutiveFails = tracker.NodeStats("node-a", now)
	if consecutiveFails != 1 {
		t.Fatalf("closing idle conn before Write should NOT add failure, got %d", consecutiveFails)
	}

	// Case 3: Write then Read response bytes -> records TTFB EMA & flips Replaceable to true!
	client3, server3 := net.Pipe()
	wrapped3 := newPassiveConn(client3, func(ttfb time.Duration) {
		tracker.RecordSuccess("node-a", "good.com", ttfb)
	}, func() {
		tracker.RecordFailure("node-a", "good.com")
	}, func() time.Time { return now })

	go func() {
		buf := make([]byte, 64)
		_, _ = server3.Read(buf)
		now = now.Add(45 * time.Millisecond)
		_, _ = server3.Write([]byte("HTTP/1.1 200 OK\r\n"))
		_ = server3.Close()
	}()

	_, _ = wrapped3.Write([]byte("PING"))
	n, err := wrapped3.Read(tmp)
	if err != nil || n == 0 {
		t.Fatalf("expected response bytes, got n=%d err=%v", n, err)
	}
	if !wrapped3.ReaderReplaceable() || !wrapped3.WriterReplaceable() {
		t.Fatalf("expected Replaceable=true after first response byte was captured")
	}
	_ = wrapped3.Close()

	ttfbMs, recentSucc, consecutiveFails := tracker.NodeStats("node-a", now)
	if consecutiveFails != 0 || recentSucc != 1 || ttfbMs <= 0 {
		t.Fatalf("expected success to clear fails & update TTFB, got ttfb=%.2f succ=%d fail=%d", ttfbMs, recentSucc, consecutiveFails)
	}
}

func TestPassiveConsecutiveFailsDoNotQuarantineGlobally(t *testing.T) {
	now := time.Unix(1700000000, 0)
	tracker := newPassiveTracker(60 * time.Minute)
	tracker.nowFn = func() time.Time { return now }

	// Repeated passive failures track consecutive count and site avoidance without global quarantine
	tracker.RecordSuccess("node-a", "a.com", 50*time.Millisecond)
	for i := 0; i < 4; i++ {
		tracker.RecordFailure("node-a", "a.com")
	}
	ttfbMs, recentSucc, consecutiveFails := tracker.NodeStats("node-a", now)
	if consecutiveFails != 4 || recentSucc != 1 || ttfbMs <= 0 {
		t.Fatalf("expected passive stats retained, got ttfb=%.2f succ=%d fail=%d", ttfbMs, recentSucc, consecutiveFails)
	}
	if !tracker.ShouldAvoidSite("node-a", "a.com", now) {
		t.Fatalf("expected per-site avoidance for a.com after failures")
	}
	if tracker.ShouldAvoidSite("node-a", "unrelated.org", now) {
		t.Fatalf("did not expect global/unrelated-site avoidance")
	}

	tracker.ClearLearned()
	_, recentSucc, consecutiveFails = tracker.NodeStats("node-a", now)
	if consecutiveFails != 0 || recentSucc != 0 {
		t.Fatalf("ClearLearned should reset passive stats")
	}
}

func TestPassiveSiteMemoryETLDPlusOneAndAvoidance(t *testing.T) {
	now := time.Unix(1700000000, 0)
	tracker := newPassiveTracker(60 * time.Minute)
	tracker.nowFn = func() time.Time { return now }

	k1 := normalizeSiteKey("api.v2.openai.com:443")
	k2 := normalizeSiteKey("chat.openai.com")
	if k1 != "openai.com" || k2 != "openai.com" {
		t.Fatalf("expected eTLD+1 normalization to 'openai.com', got %q and %q", k1, k2)
	}
	ip4 := normalizeSiteKey("104.16.132.229:443")
	if ip4 != "104.16.132.0/24" {
		t.Fatalf("expected IPv4 /24 subnet normalization, got %q", ip4)
	}

	// Remember site -> sticky lookup works
	tracker.RecordSuccess("node-a", "chat.openai.com", 40*time.Millisecond)
	if got := tracker.PreferredSiteNode("api.openai.com", now); got != "node-a" {
		t.Fatalf("expected site memory to match node-a across subdomains, got %q", got)
	}
	if count := tracker.LearnedSiteCount(now); count != 1 {
		t.Fatalf("expected 1 learned site, got %d", count)
	}

	// Per-site failure on openai.com causes avoidance ONLY for openai.com, NOT youtube.com!
	tracker.RecordSuccess("node-a", "www.youtube.com", 40*time.Millisecond)
	tracker.RecordFailure("node-a", "chat.openai.com")
	if !tracker.ShouldAvoidSite("node-a", "api.openai.com", now) {
		t.Fatalf("expected per-site avoidance for node-a on openai.com")
	}
	if tracker.ShouldAvoidSite("node-a", "m.youtube.com", now) {
		t.Fatalf("did not expect openai.com failure to trigger avoidance for youtube.com")
	}
	if got := tracker.PreferredSiteNode("chat.openai.com", now); got != "" {
		t.Fatalf("failing on remembered site should evict site preference, got %q", got)
	}

	// TTL=0 disables site memory completely
	disabledTracker := newPassiveTracker(0)
	disabledTracker.RecordSuccess("node-a", "chat.openai.com", 20*time.Millisecond)
	if got := disabledTracker.PreferredSiteNode("chat.openai.com", now); got != "" {
		t.Fatalf("siteStickyTTL=0 must disable site memory, got %q", got)
	}
	if cnt := disabledTracker.LearnedSiteCount(now); cnt != 0 {
		t.Fatalf("siteStickyTTL=0 must report 0 learned sites, got %d", cnt)
	}
}
