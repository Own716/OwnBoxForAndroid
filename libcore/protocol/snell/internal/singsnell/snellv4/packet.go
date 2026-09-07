package snellv4

import (
	"io"
	"net"
	"sync"

	snell "libcore/protocol/snell/internal/singsnell"
	"libcore/protocol/snell/internal/singsnell/internal/reuse"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/buf"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

const (
	maxUDPRequestHeaderLen = 1 + 1 + 255 + 2
	minUDPPayloadLimit     = frameSize - resetRecordOverhead
)

type clientPacketConn struct {
	net.Conn
	client *Client

	writeAccess sync.Mutex
	writer      *writer

	readAccess      sync.Mutex
	reader          *reader
	readWaitOptions N.ReadWaitOptions
}

func (c *clientPacketConn) udpRequestAddrLen(destination M.Socksaddr) int {
	if destination.IsIP() {
		if destination.Unwrap().Addr.Is4() {
			return 1 + 1 + 4 + 2
		}
		return 1 + 1 + 16 + 2
	}
	return 1 + len(destination.Fqdn) + 2
}

func (c *clientPacketConn) writeRequest() error {
	c.writeAccess.Lock()
	defer c.writeAccess.Unlock()
	if c.writer != nil {
		return nil
	}
	recordWriter := &writer{
		upstream: c.Conn,
		psk:      c.client.psk,
	}
	requestPayload := snell.Request{Command: snell.CommandUDP, ClientID: c.client.userKey}
	request := buf.NewSize(requestPayload.Len())
	err := requestPayload.Write(request)
	if err != nil {
		request.Release()
		return err
	}
	_, err = recordWriter.Write(request.Bytes())
	request.Release()
	if err != nil {
		return E.Cause(err, "write udp request")
	}
	c.writer = recordWriter
	return nil
}

func (c *clientPacketConn) readReply() (*reader, error) {
	c.readAccess.Lock()
	defer c.readAccess.Unlock()
	if c.reader != nil {
		return c.reader, nil
	}
	recordReader := &reader{upstream: c.Conn, psk: c.client.psk}
	recordReader.InitializeReadWaiter(c.readWaitOptions)
	record, err := recordReader.ReadRecord()
	if err != nil {
		return nil, E.Cause(err, "read udp reply")
	}
	cached, err := reuse.ParseReply(record)
	if err != nil {
		return nil, err
	}
	if cached != nil {
		recordReader.SetCache(cached)
	}
	c.reader = recordReader
	return recordReader, nil
}

func (c *clientPacketConn) WritePacket(buffer *buf.Buffer, destination M.Socksaddr) error {
	err := c.writeRequest()
	if err != nil {
		buffer.Release()
		return err
	}
	_, err = c.readReply()
	if err != nil {
		buffer.Release()
		return err
	}
	header := buf.With(buffer.ExtendHeader(1 + c.udpRequestAddrLen(destination)))
	common.Must(header.WriteByte(snell.UDPCommandForward))
	err = snell.WriteUDPRequestAddress(header, destination)
	if err != nil {
		buffer.Release()
		return err
	}
	if buffer.Len() > maxPayload {
		buffer.Release()
		return snell.ErrPayloadTooLarge
	}
	return c.writer.WritePacketBuffer(buffer)
}




func (c *clientPacketConn) ReadPacket(buffer *buf.Buffer) (M.Socksaddr, error) {
	recordReader, err := c.readReply()
	if err != nil {
		return M.Socksaddr{}, err
	}
	for {
		record, err := recordReader.NextRecord()
		if err != nil {
			return M.Socksaddr{}, err
		}
		if record.Len() < 8 {
			record.Release()
			return M.Socksaddr{}, E.New("snell: udp response too short")
		}
		// Surge 6.7.0 (11520): SGUDPConnectorSnellV4::socket:didReadData: silently ignores UDP
		// response frames whose address type is neither IPv4 nor IPv6.
		addressType := record.Bytes()[0]
		if addressType != snell.AddressTypeIPv4 && addressType != snell.AddressTypeIPv6 {
			record.Release()
			continue
		}
		destination, err := snell.ReadUDPResponseAddress(record)
		if err != nil {
			record.Release()
			return M.Socksaddr{}, err
		}
		if record.Len() > buffer.FreeLen() {
			record.Release()
			return M.Socksaddr{}, io.ErrShortBuffer
		}
		_, err = buffer.Write(record.Bytes())
		record.Release()
		if err != nil {
			return M.Socksaddr{}, err
		}
		return destination, nil
	}
}

func (c *clientPacketConn) FrontHeadroom() int {
	return snell.HeaderCipherLen + maxUDPRequestHeaderLen
}

func (c *clientPacketConn) RearHeadroom() int {
	return snell.AEADTagLen
}

func (c *clientPacketConn) WriterMTU() int {
	return minUDPPayloadLimit - maxUDPRequestHeaderLen
}

func (c *clientPacketConn) Upstream() any {
	return c.Conn
}

func (c *clientPacketConn) InitializeReadWaiter(options N.ReadWaitOptions) (needCopy bool) {
	c.readAccess.Lock()
	defer c.readAccess.Unlock()
	c.readWaitOptions = options
	if c.reader != nil {
		c.reader.InitializeReadWaiter(options)
	}
	return false
}

func (c *clientPacketConn) WaitReadPacket() (*buf.Buffer, M.Socksaddr, error) {
	recordReader, err := c.readReply()
	if err != nil {
		return nil, M.Socksaddr{}, err
	}
	for {
		record, err := recordReader.WaitReadBuffer()
		if err != nil {
			return nil, M.Socksaddr{}, err
		}
		if record.Len() < 8 {
			record.Release()
			return nil, M.Socksaddr{}, E.New("snell: udp response too short")
		}
		// Surge 6.7.0 (11520): SGUDPConnectorSnellV4::socket:didReadData: silently ignores UDP
		// response frames whose address type is neither IPv4 nor IPv6.
		addressType := record.Bytes()[0]
		if addressType != snell.AddressTypeIPv4 && addressType != snell.AddressTypeIPv6 {
			record.Release()
			continue
		}
		destination, err := snell.ReadUDPResponseAddress(record)
		if err != nil {
			record.Release()
			return nil, M.Socksaddr{}, err
		}
		return record, destination, nil
	}
}

var (
	_ N.PacketConn              = (*clientPacketConn)(nil)
	_ N.PacketReadWaiter        = (*clientPacketConn)(nil)
	_ N.WriterWithMTU           = (*clientPacketConn)(nil)
)
