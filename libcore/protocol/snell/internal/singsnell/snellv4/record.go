package snellv4

import (
	standardbufio "bufio"
	"crypto/cipher"
	"crypto/rand"
	"encoding/binary"
	"io"
	"math/big"
	"math/bits"
	"sync"
	"time"

	snell "libcore/protocol/snell/internal/singsnell"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/buf"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	N "github.com/sagernet/sing/common/network"
)

const (
	// Surge 6.7.0 (11520): SNConnectorEngineAdapterV4::encryptData:outData: uses these v4 frame sizing constants.
	maxPayload           = snell.MaxPayloadLen
	frameSize            = 1460
	firstRecordOverhead  = 55
	resetRecordOverhead  = 39
	initialPaddingMin    = 0x100
	initialPaddingSpan   = 0x100
	maxInitialPaddingLen = initialPaddingMin + initialPaddingSpan - 1
	// Surge 6.7.0 (11520): FUN_100012944 compares time(0) deltas against 0x1f.
	framePayloadResetInterval = 31
)

type reader struct {
	upstream         io.Reader
	bufferedUpstream *standardbufio.Reader
	psk              []byte
	cipher           cipher.AEAD
	nonce            []byte
	cache            *buf.Buffer
	readWaitOptions  N.ReadWaitOptions
}

func (r *reader) initialize() error {
	if r.cipher != nil {
		return nil
	}
	if r.bufferedUpstream == nil {
		r.bufferedUpstream = standardbufio.NewReader(r.upstream)
	}
	salt := make([]byte, snell.SaltLen)
	_, err := io.ReadFull(r.bufferedUpstream, salt)
	if err != nil {
		return err
	}
	aead, err := snell.NewAEAD(snell.DeriveKey(r.psk, salt))
	if err != nil {
		return err
	}
	r.cipher = aead
	r.nonce = make([]byte, snell.NonceLen)
	return nil
}

func (r *reader) ReadRecord() (*buf.Buffer, error) {
	err := r.initialize()
	if err != nil {
		return nil, err
	}
	headerCipher := buf.NewSize(snell.HeaderCipherLen)
	_, err = headerCipher.ReadFullFrom(r.bufferedUpstream, snell.HeaderCipherLen)
	if err != nil {
		headerCipher.Release()
		return nil, err
	}
	_, err = r.cipher.Open(headerCipher.Index(0), r.nonce, headerCipher.Bytes(), nil)
	if err != nil {
		headerCipher.Release()
		return nil, E.Cause(err, "open record header")
	}
	snell.IncreaseNonce(r.nonce)
	header := headerCipher.To(snell.HeaderPlainLen)
	if header[0] != snell.HeaderVersion {
		headerCipher.Release()
		return nil, E.Extend(snell.ErrBadVersion, header[0])
	}
	paddingLen := int(binary.BigEndian.Uint16(header[3:5]))
	payloadLen := int(binary.BigEndian.Uint16(header[5:7]))
	headerCipher.Release()

	if payloadLen == 0 {
		// Surge 6.7.0 (11520): SNConnectorEngineAdapterV4::decryptData:outData:
		// returns "ZERO CHUNK with remaining data." when the EOF record leaves
		// bytes in the internal decrypt input buffer.
		if r.bufferedUpstream.Buffered() > 0 {
			return nil, E.New("snell: zero chunk has trailing data")
		}
		return nil, io.EOF
	}

	var padding *buf.Buffer
	if paddingLen > 0 {
		padding = buf.NewSize(paddingLen)
		_, err = padding.ReadFullFrom(r.bufferedUpstream, paddingLen)
		if err != nil {
			padding.Release()
			return nil, err
		}
	}
	body := r.readWaitOptions.NewBufferSize(payloadLen + snell.AEADTagLen)
	_, err = body.ReadFullFrom(r.bufferedUpstream, payloadLen+snell.AEADTagLen)
	if err != nil {
		if padding != nil {
			padding.Release()
		}
		body.Release()
		return nil, err
	}
	if padding != nil {
		paddingBytes := padding.Bytes()
		payloadCipher := body.Bytes()
		limit := min(len(paddingBytes), len(payloadCipher))
		for index := 0; index < limit; index += 2 {
			paddingBytes[index], payloadCipher[index] = payloadCipher[index], paddingBytes[index]
		}
		padding.Release()
	}
	payloadCipher := body.Bytes()
	_, err = r.cipher.Open(payloadCipher[:0], r.nonce, payloadCipher, nil)
	if err != nil {
		body.Release()
		return nil, E.Cause(err, "open record payload")
	}
	snell.IncreaseNonce(r.nonce)
	body.Truncate(payloadLen)
	r.readWaitOptions.PostReturn(body)
	return body, nil
}

func (r *reader) NextRecord() (*buf.Buffer, error) {
	record := r.cache
	if record != nil {
		r.cache = nil
		if record.IsEmpty() {
			record.Release()
		} else {
			return record, nil
		}
	}
	return r.ReadRecord()
}

func (r *reader) SetCache(cache *buf.Buffer) {
	r.cache = cache
}

func (r *reader) ReleaseCache() {
	if r.cache != nil {
		r.cache.Release()
		r.cache = nil
	}
}

func (r *reader) Read(p []byte) (n int, err error) {
	for {
		if r.cache != nil {
			if r.cache.IsEmpty() {
				r.cache.Release()
				r.cache = nil
			} else {
				n = copy(p, r.cache.Bytes())
				r.cache.Advance(n)
				return
			}
		}
		r.cache, err = r.ReadRecord()
		if err != nil {
			return
		}
	}
}

func (r *reader) ReadBuffer(buffer *buf.Buffer) error {
	for {
		if r.cache != nil {
			if r.cache.IsEmpty() {
				r.cache.Release()
				r.cache = nil
			} else {
				n, _ := buffer.Write(r.cache.Bytes())
				r.cache.Advance(n)
				return nil
			}
		}
		var err error
		r.cache, err = r.ReadRecord()
		if err != nil {
			return err
		}
	}
}

func (r *reader) InitializeReadWaiter(options N.ReadWaitOptions) (needCopy bool) {
	r.readWaitOptions = options
	return false
}

func (r *reader) WaitReadBuffer() (buffer *buf.Buffer, err error) {
	for {
		if r.cache == nil {
			return r.ReadRecord()
		}
		buffer = r.cache
		r.cache = nil
		if buffer.IsEmpty() {
			buffer.Release()
			continue
		}
		if buffer.Start() >= r.readWaitOptions.FrontHeadroom && buffer.FreeLen() >= r.readWaitOptions.RearHeadroom {
			return
		}
		buffer = r.readWaitOptions.Copy(buffer)
		return buffer, nil
	}
}

func (r *reader) Upstream() any {
	return r.upstream
}

type writer struct {
	upstream          io.Writer
	psk               []byte
	cipher            cipher.AEAD
	nonce             []byte
	salt              []byte
	saltSent          bool
	initialPaddingLen int
	payloadLimit      int
	lastWriteUnix     int64
	access            sync.Mutex
}

func (w *writer) initialize() error {
	if w.cipher != nil {
		return nil
	}
	salt := make([]byte, snell.SaltLen)
	_, err := io.ReadFull(rand.Reader, salt)
	if err != nil {
		return err
	}
	aead, err := snell.NewAEAD(snell.DeriveKey(w.psk, salt))
	if err != nil {
		return err
	}
	paddingDelta, err := rand.Int(rand.Reader, big.NewInt(initialPaddingSpan))
	if err != nil {
		return err
	}
	w.cipher = aead
	w.nonce = make([]byte, snell.NonceLen)
	w.salt = salt
	w.initialPaddingLen = initialPaddingMin + int(paddingDelta.Int64())
	return nil
}

func (w *writer) payloadLimitFor(nowUnix int64) int {
	if !w.saltSent {
		return frameSize - firstRecordOverhead - w.initialPaddingLen
	}
	if w.lastWriteUnix != 0 && nowUnix-w.lastWriteUnix < framePayloadResetInterval {
		if w.payloadLimit > 0 {
			return w.payloadLimit
		}
		return frameSize - resetRecordOverhead
	}
	return frameSize - resetRecordOverhead
}

func (w *writer) advancePayloadLimit(payloadLimit int, nowUnix int64) {
	w.lastWriteUnix = nowUnix
	if payloadLimit <= maxPayload-1 {
		nextPayloadLimit := payloadLimit + frameSize - resetRecordOverhead
		w.payloadLimit = min(nextPayloadLimit, maxPayload)
	} else {
		w.payloadLimit = maxPayload
	}
}

func (w *writer) framePaddingLen(payloadLen int) int {
	if w.saltSent || payloadLen == 0 {
		return 0
	}
	return w.initialPaddingLen
}

func (w *writer) writeBytesLocked(p []byte) (n int, err error) {
	err = w.initialize()
	if err != nil {
		return 0, err
	}
	nowUnix := time.Now().Unix()
	payloadLimit := w.payloadLimitFor(nowUnix)
	if payloadLimit <= 0 || payloadLimit > maxPayload {
		panic("snell: invalid v4 payload limit")
	}
	// Surge 6.7.0 (11520): SNConnectorEngineAdapterV4::encryptData:outData: updates the next payload limit once for
	// each encryptData call, then splits that call using the same limit.
	w.advancePayloadLimit(payloadLimit, nowUnix)
	for len(p) > 0 {
		dataLen := min(len(p), payloadLimit)
		paddingLen := w.framePaddingLen(dataLen)
		err = w.writeSliceRecordLocked(p[:dataLen], paddingLen)
		if err != nil {
			return n, err
		}
		n += dataLen
		p = p[dataLen:]
	}
	return n, nil
}

func (w *writer) makeSliceRecordLocked(payload []byte, paddingLen int) (*buf.Buffer, error) {
	err := w.initialize()
	if err != nil {
		return nil, err
	}
	if len(payload) > maxPayload || paddingLen > maxPayload {
		panic("snell: v4 record exceeds maximum")
	}
	if len(payload) == 0 && paddingLen != 0 {
		panic("snell: zero-length v4 record carries padding")
	}
	saltLen := 0
	if !w.saltSent {
		saltLen = snell.SaltLen
	}
	payloadCipherLen := 0
	if len(payload) > 0 {
		payloadCipherLen = len(payload) + snell.AEADTagLen
	}
	out := buf.NewSize(saltLen + snell.HeaderCipherLen + paddingLen + payloadCipherLen)
	if saltLen > 0 {
		common.Must1(out.Write(w.salt))
		w.saltSent = true
	}
	header := out.Extend(snell.HeaderCipherLen)
	header[0] = snell.HeaderVersion
	header[1] = 0
	header[2] = 0
	binary.BigEndian.PutUint16(header[3:5], uint16(paddingLen))
	binary.BigEndian.PutUint16(header[5:7], uint16(len(payload)))
	w.cipher.Seal(header[:0], w.nonce, header[:snell.HeaderPlainLen], nil)
	snell.IncreaseNonce(w.nonce)
	padding := out.Extend(paddingLen)
	if len(payload) > 0 {
		payloadCipher := out.Extend(payloadCipherLen)
		copy(payloadCipher, payload)
		w.cipher.Seal(payloadCipher[:0], w.nonce, payloadCipher[:len(payload)], nil)
		snell.IncreaseNonce(w.nonce)
		if paddingLen > 0 {
			err = w.fillPadding(padding, payloadCipher)
			if err != nil {
				out.Release()
				return nil, err
			}
			limit := min(len(padding), len(payloadCipher))
			for index := 0; index < limit; index += 2 {
				padding[index], payloadCipher[index] = payloadCipher[index], padding[index]
			}
		}
	}
	return out, nil
}

func (w *writer) writeSliceRecordLocked(payload []byte, paddingLen int) error {
	out, err := w.makeSliceRecordLocked(payload, paddingLen)
	if err != nil {
		return err
	}
	return w.writeRecordLocked(out)
}

func (w *writer) writeRecordLocked(record *buf.Buffer) error {
	defer record.Release()
	return common.Error(w.upstream.Write(record.Bytes()))
}

func (w *writer) randomInt31() (int, error) {
	var randomBytes [4]byte
	_, err := io.ReadFull(rand.Reader, randomBytes[:])
	if err != nil {
		return 0, err
	}
	return int(binary.LittleEndian.Uint32(randomBytes[:]) & 0x7fffffff), nil
}

func (w *writer) makeBufferRecordLocked(buffer *buf.Buffer, paddingLen int) (*buf.Buffer, error) {
	err := w.initialize()
	if err != nil {
		return nil, err
	}
	dataLen := buffer.Len()
	if dataLen > maxPayload || paddingLen > maxPayload {
		panic("snell: v4 record exceeds maximum")
	}
	if dataLen == 0 && paddingLen != 0 {
		panic("snell: zero-length v4 record carries padding")
	}
	saltLen := 0
	if !w.saltSent {
		saltLen = snell.SaltLen
	}
	frontLen := saltLen + snell.HeaderCipherLen + paddingLen
	prefix := buffer.ExtendHeader(frontLen)
	if saltLen > 0 {
		copy(prefix[:saltLen], w.salt)
		w.saltSent = true
	}
	header := prefix[saltLen : saltLen+snell.HeaderCipherLen]
	header[0] = snell.HeaderVersion
	header[1] = 0
	header[2] = 0
	binary.BigEndian.PutUint16(header[3:5], uint16(paddingLen))
	binary.BigEndian.PutUint16(header[5:7], uint16(dataLen))
	w.cipher.Seal(header[:0], w.nonce, header[:snell.HeaderPlainLen], nil)
	snell.IncreaseNonce(w.nonce)
	if dataLen > 0 {
		payloadCipher := buffer.From(frontLen)
		buffer.Extend(snell.AEADTagLen)
		w.cipher.Seal(payloadCipher[:0], w.nonce, payloadCipher[:dataLen], nil)
		snell.IncreaseNonce(w.nonce)
		if paddingLen > 0 {
			padding := prefix[saltLen+snell.HeaderCipherLen:]
			payloadCipher = buffer.From(frontLen)
			err = w.fillPadding(padding, payloadCipher)
			if err != nil {
				return nil, err
			}
			limit := min(len(padding), len(payloadCipher))
			for index := 0; index < limit; index += 2 {
				padding[index], payloadCipher[index] = payloadCipher[index], padding[index]
			}
		}
	}
	return buffer, nil
}

func (w *writer) writeBufferRecordLocked(buffer *buf.Buffer, paddingLen int) error {
	record, err := w.makeBufferRecordLocked(buffer, paddingLen)
	if err != nil {
		return err
	}
	return w.writeRecordLocked(record)
}

func (w *writer) fillPadding(padding []byte, payloadCipher []byte) error {
	if len(padding) == 0 {
		return nil
	}
	payloadOnes := 0
	// Surge 6.7.0 (11520): FUN_100012b24 counts ones only over complete
	// 32-bit chunks, but computes zeros against the full payload cipher length.
	payloadCountLen := len(payloadCipher) &^ 3
	for _, payloadByte := range payloadCipher[:payloadCountLen] {
		payloadOnes += bits.OnesCount8(payloadByte)
	}
	payloadZeros := 8*len(payloadCipher) - payloadOnes
	if payloadZeros <= 0 {
		_, err := io.ReadFull(rand.Reader, padding)
		return err
	}

	ratio := float64(payloadOnes) / float64(payloadZeros)
	if ratio <= 0.5 || ratio >= 1.6 {
		_, err := io.ReadFull(rand.Reader, padding)
		return err
	}

	targetRatioBase := 1.6
	if payloadZeros < payloadOnes {
		targetRatioBase = 0.4
	}
	// Surge 6.7.0 (11520): FUN_100012944 derives the target padding ratio as
	// base + rand()/2147483647.0/10, using the process rand() 31-bit range.
	randomValue, err := w.randomInt31()
	if err != nil {
		return err
	}
	targetRatio := targetRatioBase + float64(randomValue)/2147483647.0/10
	totalBits := 8 * (len(padding) + len(payloadCipher))
	targetOnes := int(float64(totalBits)*(targetRatio/(targetRatio+1)) - float64(payloadOnes))
	if targetOnes < 0 || targetOnes > 8*len(padding) {
		_, err = io.ReadFull(rand.Reader, padding)
		return err
	}
	return w.fillPaddingWithBitCount(padding, targetOnes)
}

func (w *writer) fillPaddingWithBitCount(padding []byte, oneBits int) error {
	totalBits := 8 * len(padding)
	if oneBits < 0 || oneBits > totalBits {
		panic("snell: invalid padding bit count")
	}
	bitset := make([]byte, totalBits)
	for index := range oneBits {
		bitset[index] = 1
	}
	// Surge 6.7.0 (11520): FUN_100012eb8 shuffles from the front and maps
	// rand() with division by RAND_MAX/remaining+1, preserving its modulo bias.
	position := 0
	remaining := totalBits
	for remaining != 1 {
		randomValue, err := w.randomInt31()
		if err != nil {
			return err
		}
		divisor := 0
		if remaining != 0 {
			divisor = 0x7fffffff / remaining
		}
		swapIndex := position + randomValue/(divisor+1)
		bitset[swapIndex], bitset[position] = bitset[position], bitset[swapIndex]
		position++
		remaining--
	}
	clear(padding)
	for index, bit := range bitset {
		if bit == 1 {
			padding[index/8] |= 1 << uint(index%8)
		}
	}
	return nil
}

func (w *writer) Write(p []byte) (n int, err error) {
	w.access.Lock()
	defer w.access.Unlock()
	if len(p) == 0 {
		err = w.initialize()
		if err != nil {
			return 0, err
		}
		nowUnix := time.Now().Unix()
		payloadLimit := w.payloadLimitFor(nowUnix)
		if payloadLimit <= 0 || payloadLimit > maxPayload {
			panic("snell: invalid v4 payload limit")
		}
		// Surge 6.7.0 (11520): FUN_100012944 advances the growth window before
		// calling FUN_100012b24 even when encrypting a zero-length EOF record.
		w.advancePayloadLimit(payloadLimit, nowUnix)
		return 0, w.writeSliceRecordLocked(nil, 0)
	}
	return w.writeBytesLocked(p)
}

func (w *writer) WriteZeroChunk() error {
	w.access.Lock()
	defer w.access.Unlock()
	err := w.initialize()
	if err != nil {
		return err
	}
	nowUnix := time.Now().Unix()
	payloadLimit := w.payloadLimitFor(nowUnix)
	if payloadLimit <= 0 || payloadLimit > maxPayload {
		panic("snell: invalid v4 payload limit")
	}
	// Surge 6.7.0 (11520): FUN_100012944 advances the growth window before
	// calling FUN_100012b24 even when encrypting a zero-length EOF record.
	w.advancePayloadLimit(payloadLimit, nowUnix)
	return w.writeSliceRecordLocked(nil, 0)
}

func (w *writer) WriteBuffer(buffer *buf.Buffer) error {
	defer buffer.Release()
	dataLen := buffer.Len()
	if dataLen == 0 {
		return nil
	}
	w.access.Lock()
	defer w.access.Unlock()
	err := w.initialize()
	if err != nil {
		return err
	}
	nowUnix := time.Now().Unix()
	payloadLimit := w.payloadLimitFor(nowUnix)
	if payloadLimit <= 0 || payloadLimit > maxPayload {
		panic("snell: invalid v4 payload limit")
	}
	if dataLen > payloadLimit {
		_, err = w.writeBytesLocked(buffer.Bytes())
		return err
	}
	w.advancePayloadLimit(payloadLimit, nowUnix)
	paddingLen := w.framePaddingLen(dataLen)
	return w.writeBufferRecordLocked(buffer, paddingLen)
}

func (w *writer) CreateVectorisedWriter() (N.VectorisedWriter, bool) {
	upstreamWriter, created := bufio.CreateVectorisedWriter(w.upstream)
	if !created {
		return nil, false
	}
	return w.CreateVectorisedWriterFor(upstreamWriter), true
}

func (w *writer) CreateVectorisedWriterFor(upstream N.VectorisedWriter) N.VectorisedWriter {
	return &vectorisedWriter{writer: w, upstream: upstream}
}

type vectorisedWriter struct {
	writer   *writer
	upstream N.VectorisedWriter
}

func (w *vectorisedWriter) WriteVectorised(buffers []*buf.Buffer) error {
	var records []*buf.Buffer
	defer func() {
		buf.ReleaseMulti(records)
	}()
	recordWriter := w.writer
	recordWriter.access.Lock()
	defer recordWriter.access.Unlock()
	payloadLimit := 0
	for index, buffer := range buffers {
		if buffer.IsEmpty() {
			buffer.Release()
			continue
		}
		if payloadLimit == 0 {
			err := recordWriter.initialize()
			if err != nil {
				buffer.Release()
				buf.ReleaseMulti(buffers[index+1:])
				return err
			}
			nowUnix := time.Now().Unix()
			payloadLimit = recordWriter.payloadLimitFor(nowUnix)
			if payloadLimit <= 0 || payloadLimit > maxPayload {
				panic("snell: invalid v4 payload limit")
			}
			// Surge 6.7.0 (11520): SNConnectorEngineAdapterV4::encryptData:outData: advances the growth window once per
			// call; a vectorised write is one caller-visible write here.
			recordWriter.advancePayloadLimit(payloadLimit, nowUnix)
		}
		dataLen := buffer.Len()
		for data := buffer.Bytes(); len(data) > 0; {
			recordLen := min(len(data), payloadLimit)
			paddingLen := recordWriter.framePaddingLen(recordLen)
			var record *buf.Buffer
			var err error
			if len(data) == dataLen && dataLen <= payloadLimit {
				record, err = recordWriter.makeBufferRecordLocked(buffer, paddingLen)
				if err != nil {
					buffer.Release()
					buf.ReleaseMulti(buffers[index+1:])
					return err
				}
				buffer = nil
			} else {
				record, err = recordWriter.makeSliceRecordLocked(data[:recordLen], paddingLen)
				if err != nil {
					buffer.Release()
					buf.ReleaseMulti(buffers[index+1:])
					return err
				}
			}
			data = data[recordLen:]
			records = append(records, record)
		}
		if buffer != nil {
			buffer.Release()
		}
	}
	if len(records) == 0 {
		return nil
	}
	flushRecords := records
	records = nil
	return w.upstream.WriteVectorised(flushRecords)
}

func (w *writer) CreatePacketVectorisedWriterFor(upstream N.VectorisedWriter) N.VectorisedWriter {
	return &packetVectorisedWriter{writer: w, upstream: upstream}
}

type packetVectorisedWriter struct {
	writer   *writer
	upstream N.VectorisedWriter
}

func (w *packetVectorisedWriter) WriteVectorised(buffers []*buf.Buffer) error {
	var records []*buf.Buffer
	defer func() {
		buf.ReleaseMulti(records)
	}()
	recordWriter := w.writer
	recordWriter.access.Lock()
	defer recordWriter.access.Unlock()
	err := recordWriter.initialize()
	if err != nil {
		buf.ReleaseMulti(buffers)
		return err
	}
	for index, buffer := range buffers {
		if buffer.IsEmpty() {
			buffer.Release()
			continue
		}
		nowUnix := time.Now().Unix()
		payloadLimit := recordWriter.payloadLimitFor(nowUnix)
		if payloadLimit <= 0 || payloadLimit > maxPayload {
			panic("snell: invalid v4 payload limit")
		}
		dataLen := buffer.Len()
		if dataLen > payloadLimit {
			buffer.Release()
			buf.ReleaseMulti(buffers[index+1:])
			return snell.ErrPayloadTooLarge
		}
		recordWriter.advancePayloadLimit(payloadLimit, nowUnix)
		paddingLen := recordWriter.framePaddingLen(dataLen)
		record, err := recordWriter.makeBufferRecordLocked(buffer, paddingLen)
		if err != nil {
			buffer.Release()
			buf.ReleaseMulti(buffers[index+1:])
			return err
		}
		records = append(records, record)
	}
	if len(records) == 0 {
		return nil
	}
	flushRecords := records
	records = nil
	return w.upstream.WriteVectorised(flushRecords)
}

func (w *writer) WritePacketBuffer(buffer *buf.Buffer) error {
	defer buffer.Release()
	dataLen := buffer.Len()
	w.access.Lock()
	defer w.access.Unlock()
	err := w.initialize()
	if err != nil {
		return err
	}
	nowUnix := time.Now().Unix()
	payloadLimit := w.payloadLimitFor(nowUnix)
	if payloadLimit <= 0 || payloadLimit > maxPayload {
		panic("snell: invalid v4 payload limit")
	}
	if dataLen > payloadLimit {
		return snell.ErrPayloadTooLarge
	}
	w.advancePayloadLimit(payloadLimit, nowUnix)
	paddingLen := w.framePaddingLen(dataLen)
	frontHeadroom := snell.HeaderCipherLen + paddingLen
	if !w.saltSent {
		frontHeadroom += snell.SaltLen
	}
	if buffer.Start() < frontHeadroom || buffer.FreeLen() < snell.AEADTagLen {
		return w.writeSliceRecordLocked(buffer.Bytes(), paddingLen)
	}
	return w.writeBufferRecordLocked(buffer, paddingLen)
}

func (w *writer) FrontHeadroom() int {
	if w.saltSent {
		return snell.HeaderCipherLen
	}
	return snell.SaltLen + snell.HeaderCipherLen + maxInitialPaddingLen
}

func (w *writer) RearHeadroom() int {
	return snell.AEADTagLen
}

func (w *writer) WriterMTU() int {
	return maxPayload
}

func (w *writer) Upstream() any {
	return w.upstream
}

var (
	_ N.ExtendedReader         = (*reader)(nil)
	_ N.ReadWaiter             = (*reader)(nil)
	_ N.ExtendedWriter         = (*writer)(nil)
	_ N.VectorisedWriteCreator = (*writer)(nil)
	_ N.VectorisedWriter       = (*vectorisedWriter)(nil)
	_ N.VectorisedWriter       = (*packetVectorisedWriter)(nil)
	_ N.FrontHeadroom          = (*writer)(nil)
	_ N.RearHeadroom           = (*writer)(nil)
	_ N.WriterWithMTU          = (*writer)(nil)
)
