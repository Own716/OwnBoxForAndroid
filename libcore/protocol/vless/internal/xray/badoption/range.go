package badoption

import (
	"encoding/json"
	"fmt"
	"strconv"
	"strings"

	E "github.com/sagernet/sing/common/exceptions"
	"libcore/protocol/vless/internal/xray/crypto"
)

type Range struct {
	From int32 `json:"from"`
	To   int32 `json:"to"`
}

func (c *Range) Build() *Range {
	return (*Range)(c)
}

func (c *Range) MarshalJSON() ([]byte, error) {
	return json.Marshal(fmt.Sprintf("%d-%d", c.From, c.To))
}

func (c *Range) UnmarshalJSON(content []byte) error {
	var num float64
	if err := json.Unmarshal(content, &num); err == nil {
		val := int32(num)
		*c = Range{From: val, To: val}
		return nil
	}

	var stringValue string
	if err := json.Unmarshal(content, &stringValue); err == nil {
		stringValue = strings.TrimSpace(stringValue)
		if stringValue == "" {
			*c = Range{From: 0, To: 0}
			return nil
		}
		parts := strings.Split(stringValue, "-")
		if len(parts) == 2 {
			from, err := strconv.ParseInt(strings.TrimSpace(parts[0]), 10, 32)
			if err != nil {
				return err
			}
			to, err := strconv.ParseInt(strings.TrimSpace(parts[1]), 10, 32)
			if err != nil {
				return err
			}
			if int32(from) > int32(to) {
				return E.New("invalid range")
			}
			*c = Range{From: int32(from), To: int32(to)}
			return nil
		}
		single, err := strconv.ParseInt(stringValue, 10, 32)
		if err != nil {
			return err
		}
		*c = Range{From: int32(single), To: int32(single)}
		return nil
	}

	var rangeValue struct {
		From int32 `json:"from"`
		To   int32 `json:"to"`
	}
	if err := json.Unmarshal(content, &rangeValue); err != nil {
		return err
	}
	if rangeValue.From > rangeValue.To {
		return E.New("invalid range")
	}
	*c = Range{From: rangeValue.From, To: rangeValue.To}
	return nil
}

func (c Range) Rand() int32 {
	return int32(crypto.RandBetween(int64(c.From), int64(c.To)))
}
