package meldsun0.utp.network;

import meldsun0.utp.data.UtpPacket;

import java.io.IOException;

public interface TransportLayer<T extends TransportAddress> {

  void sendPacket(UtpPacket packet, T remoteAddress) throws IOException;

  void close(long connectionId, T remoteAddress);
}
