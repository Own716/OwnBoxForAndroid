package urltest

import (
	"testing"
)

func TestGetFallbackLink(t *testing.T) {
	cfLink := "https://cp.cloudflare.com/generate_204"
	fb1 := GetFallbackLink(cfLink)
	if fb1 != DefaultFallbackURL {
		t.Fatalf("expected %s for cloudflare link, got %s", DefaultFallbackURL, fb1)
	}

	googleLink := "https://www.gstatic.com/generate_204"
	fb2 := GetFallbackLink(googleLink)
	if fb2 != DefaultCFURL {
		t.Fatalf("expected %s for non-cloudflare link, got %s", DefaultCFURL, fb2)
	}
}
