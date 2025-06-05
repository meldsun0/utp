package meldsun0.utp.network.udp;

import meldsun0.utp.data.UtpPacket;
import meldsun0.utp.message.UTPWireMessageDecoder;
import meldsun0.utp.network.TransportLayer;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;

import static meldsun0.utp.data.UtpPacketUtils.MAX_UDP_HEADER_LENGTH;
import static meldsun0.utp.data.UtpPacketUtils.MAX_UTP_PACKET_LENGTH;

public class UDPTransportLayer implements TransportLayer<UDPAddress> {

  protected DatagramSocket socket;
  private final Object sendLock = new Object();

  public UDPTransportLayer(int port) {
    try {
      this.socket = new DatagramSocket(port);
    } catch (SocketException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void sendPacket(UtpPacket packet, UDPAddress remoteAddress) throws IOException {
    synchronized (sendLock) {
      DatagramPacket UDPPacket = createDatagramPacket(packet);
      UDPPacket.setAddress(remoteAddress.getAddress());
      UDPPacket.setPort(remoteAddress.getPort());
      this.socket.send(UDPPacket);
    }
  }

  public UtpPacket onPacketReceive() throws IOException {
    byte[] buffer = new byte[MAX_UDP_HEADER_LENGTH + MAX_UTP_PACKET_LENGTH];
    DatagramPacket dgpkt = new DatagramPacket(buffer, buffer.length);
    this.socket.receive(dgpkt);
    return UTPWireMessageDecoder.decode(dgpkt);
  }

  @Override
  public void close(long connectionId, UDPAddress remoteAddress) {
    this.socket.close();
  }

  public static DatagramPacket createDatagramPacket(UtpPacket packet) throws IOException {
    byte[] utpPacketBytes = packet.toByteArray();
    int length = packet.getPacketLength();
    return new DatagramPacket(utpPacketBytes, length);
  }
}
