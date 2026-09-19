package xhttp

import (
	"crypto/rand"
	"math"
	"net/http"
	"net/url"
	"strings"

	"golang.org/x/net/http2/hpack"
)

const (
	PlacementPath          = "path"
	PlacementQuery         = "query"
	PlacementHeader        = "header"
	PlacementCookie        = "cookie"
	PlacementQueryInHeader = "queryinheader"
)

const (
	PaddingMethodRepeatX  = "repeat-x"
	PaddingMethodTokenish = "tokenish"
	PaddingMethodRandom   = "random"
)

const charsetBase62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
const avgHuffmanBytesPerCharBase62 = 0.8
const validationTolerance = 2

func randStringFromCharset(n int, charset string) (string, bool) {
	if n <= 0 || len(charset) == 0 {
		return "", false
	}

	m := len(charset)
	limit := byte(256 - (256 % m))

	result := make([]byte, n)
	i := 0

	buf := make([]byte, 256)
	for i < n {
		if _, err := rand.Read(buf); err != nil {
			return "", false
		}
		for _, rb := range buf {
			if rb >= limit {
				continue
			}
			result[i] = charset[int(rb)%m]
			i++
			if i == n {
				break
			}
		}
	}

	return string(result), true
}

func absInt(x int) int {
	if x < 0 {
		return -x
	}
	return x
}

// GenerateTokenishPaddingBase62 generates a random base62 string whose Huffman-encoded length
// closely matches targetHuffmanBytes, matching Xray SplitHTTP obfuscation.
func GenerateTokenishPaddingBase62(targetHuffmanBytes int) string {
	n := int(math.Ceil(float64(targetHuffmanBytes) / avgHuffmanBytesPerCharBase62))
	if n < 1 {
		n = 1
	}

	randBase62Str, ok := randStringFromCharset(n, charsetBase62)
	if !ok {
		return strings.Repeat("X", targetHuffmanBytes)
	}

	const maxIter = 150
	adjustChar := byte('X')

	for iter := 0; iter < maxIter; iter++ {
		currentLength := int(hpack.HuffmanEncodeLength(randBase62Str))
		diff := currentLength - targetHuffmanBytes

		if absInt(diff) <= validationTolerance {
			return randBase62Str
		}

		if diff < 0 {
			randBase62Str += string(adjustChar)
			if adjustChar == 'X' {
				adjustChar = 'Z'
			} else {
				adjustChar = 'X'
			}
		} else {
			if len(randBase62Str) <= 1 {
				return randBase62Str
			}
			randBase62Str = randBase62Str[:len(randBase62Str)-1]
		}
	}

	return randBase62Str
}

func GeneratePadding(method string, length int) string {
	if length <= 0 {
		return ""
	}
	switch strings.ToLower(strings.TrimSpace(method)) {
	case PaddingMethodTokenish:
		return GenerateTokenishPaddingBase62(length)
	case PaddingMethodRandom:
		s, ok := randStringFromCharset(length, charsetBase62)
		if ok {
			return s
		}
		return strings.Repeat("X", length)
	case PaddingMethodRepeatX, "":
		fallthrough
	default:
		return strings.Repeat("X", length)
	}
}

// ApplyPadding applies X-Padding to the HTTP request based on options.
func (c *V2RayXHTTPBaseOptions) ApplyPadding(req *http.Request) {
	paddingLen := int(c.GetNormalizedXPaddingBytes().Rand())
	if paddingLen <= 0 {
		return
	}

	if !c.XPaddingObfsMode && c.XPaddingPlacement == "" {
		// Standard legacy Xray SplitHTTP compatibility: Referer + X-Padding
		paddingStr := strings.Repeat("X", paddingLen)
		if req.URL != nil {
			u := *req.URL
			if u.RawQuery != "" {
				u.RawQuery += "&x_padding=" + paddingStr
			} else {
				u.RawQuery = "x_padding=" + paddingStr
			}
			req.Header.Set("Referer", u.String())
		}
		req.Header.Set("X-Padding", paddingStr)
		return
	}

	// Obfuscation mode
	paddingStr := GeneratePadding(c.XPaddingMethod, paddingLen)
	placement := strings.ToLower(strings.TrimSpace(c.XPaddingPlacement))
	if placement == "" {
		placement = PlacementHeader
	}

	key := strings.TrimSpace(c.XPaddingKey)
	if key == "" {
		key = "x_padding"
	}

	headerName := strings.TrimSpace(c.XPaddingHeader)

	switch placement {
	case PlacementHeader:
		if headerName == "" {
			if c.XPaddingKey != "" {
				headerName = c.XPaddingKey
			} else {
				headerName = "X-Padding"
			}
		}
		req.Header.Set(headerName, paddingStr)

	case PlacementCookie:
		req.AddCookie(&http.Cookie{
			Name:  key,
			Value: paddingStr,
		})

	case PlacementQuery:
		if req.URL != nil {
			q := req.URL.Query()
			q.Set(key, paddingStr)
			req.URL.RawQuery = q.Encode()
		}

	case PlacementQueryInHeader:
		if headerName == "" {
			headerName = "Referer"
		}
		existing := req.Header.Get(headerName)
		if existing != "" {
			if u, err := url.Parse(existing); err == nil {
				uq := u.Query()
				uq.Set(key, paddingStr)
				u.RawQuery = uq.Encode()
				req.Header.Set(headerName, u.String())
				return
			}
			req.Header.Set(headerName, existing+"&"+key+"="+url.QueryEscape(paddingStr))
		} else if req.URL != nil {
			u := *req.URL
			uq := u.Query()
			uq.Set(key, paddingStr)
			u.RawQuery = uq.Encode()
			req.Header.Set(headerName, u.String())
		}

	default:
		req.Header.Set("X-Padding", paddingStr)
	}
}
