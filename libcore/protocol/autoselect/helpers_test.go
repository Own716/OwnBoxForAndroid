package autoselect

import (
	"time"

	"github.com/sagernet/sing/common/json/badoption"
)

func intPtr(v int) *int                           { return &v }
func uint16Ptr(v uint16) *uint16                  { return &v }
func durationPtr(d time.Duration) *badoption.Duration { bd := badoption.Duration(d); return &bd }
