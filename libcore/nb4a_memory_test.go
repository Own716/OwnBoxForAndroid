package libcore

import "testing"

func TestParseMemoryLimitMiB(t *testing.T) {
	cases := []struct {
		name string
		raw  string
		want int64
	}{
		{name: "empty", raw: "", want: 384},
		{name: "whitespace", raw: "   \n\t ", want: 384},
		{name: "invalid", raw: "abc", want: 384},
		{name: "zero", raw: "0", want: 384},
		{name: "negative", raw: "-64", want: 384},
		{name: "below_min_clamped", raw: "32", want: 96},
		{name: "exact_min", raw: "96", want: 96},
		{name: "low_ram", raw: "128", want: 128},
		{name: "mid_ram", raw: "256\n", want: 256},
		{name: "high_ram", raw: " 384 ", want: 384},
		{name: "upper_mid", raw: "512", want: 512},
		{name: "exact_max", raw: "768", want: 768},
		{name: "above_max_clamped", raw: "1024", want: 768},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := parseMemoryLimitMiB(tc.raw); got != tc.want {
				t.Fatalf("parseMemoryLimitMiB(%q) = %d, want %d", tc.raw, got, tc.want)
			}
		})
	}
}
