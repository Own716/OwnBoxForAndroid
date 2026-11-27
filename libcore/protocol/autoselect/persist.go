package autoselect

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
	"time"
)

const (
	persistedCacheFileName = "autoselect_cache.json"
	persistedMaxAge        = 24 * time.Hour
)

type persistedSiteRecord struct {
	SiteKey     string `json:"s"`
	NodeTag     string `json:"n"`
	UpdatedAtMs int64  `json:"t"`
}

type persistedGroupState struct {
	ActiveTag string                `json:"active_tag,omitempty"`
	Delays    map[string]uint16     `json:"delays,omitempty"`
	Sites     []persistedSiteRecord `json:"sites,omitempty"`
	SavedAtMs int64                 `json:"saved_at_ms,omitempty"`
}

type persistedCacheRoot struct {
	Groups map[string]persistedGroupState `json:"groups"`
}

var (
	persistCacheMu           sync.Mutex
	persistCacheFileOverride string
)

func cacheFilePath() string {
	if persistCacheFileOverride != "" {
		return persistCacheFileOverride
	}
	return persistedCacheFileName
}

func loadPersistedGroupState(cacheKey string, now time.Time) (persistedGroupState, bool) {
	if cacheKey == "" {
		return persistedGroupState{}, false
	}
	persistCacheMu.Lock()
	defer persistCacheMu.Unlock()

	raw, err := os.ReadFile(cacheFilePath())
	if err != nil || len(raw) == 0 {
		return persistedGroupState{}, false
	}
	var root persistedCacheRoot
	if err := json.Unmarshal(raw, &root); err != nil || len(root.Groups) == 0 {
		return persistedGroupState{}, false
	}
	st, ok := root.Groups[cacheKey]
	if !ok {
		return persistedGroupState{}, false
	}
	if st.SavedAtMs > 0 {
		savedAt := time.UnixMilli(st.SavedAtMs)
		if now.Sub(savedAt) > persistedMaxAge {
			return persistedGroupState{}, false
		}
	}
	return st, true
}

func savePersistedGroupState(cacheKey string, state persistedGroupState) {
	if cacheKey == "" {
		return
	}
	persistCacheMu.Lock()
	defer persistCacheMu.Unlock()

	path := cacheFilePath()
	root := persistedCacheRoot{
		Groups: make(map[string]persistedGroupState),
	}
	if raw, err := os.ReadFile(path); err == nil && len(raw) > 0 {
		_ = json.Unmarshal(raw, &root)
		if root.Groups == nil {
			root.Groups = make(map[string]persistedGroupState)
		}
	}
	root.Groups[cacheKey] = state

	encoded, err := json.Marshal(root)
	if err != nil {
		return
	}
	if dir := filepath.Dir(path); dir != "" && dir != "." {
		_ = os.MkdirAll(dir, 0755)
	}
	tmpPath := path + ".tmp"
	if err := os.WriteFile(tmpPath, encoded, 0600); err != nil {
		_ = os.Remove(tmpPath)
		return
	}
	if err := os.Rename(tmpPath, path); err != nil {
		_ = os.Remove(tmpPath)
	}
}
