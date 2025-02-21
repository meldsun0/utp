package examples;

import meldsun0.utp.UTPClient;
import meldsun0.utp.network.udp.UDPAddress;
import meldsun0.utp.network.udp.UDPTransportLayer;
import org.apache.tuweni.bytes.Bytes;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class Offer {

  public static void main(String args[])
      throws ExecutionException, InterruptedException, IOException {
    UDPAddress udpaddress = new UDPAddress("localhost", 13345);
    UDPTransportLayer udpTransportLayer = new UDPTransportLayer();

    UTPClient chanel = new UTPClient(udpTransportLayer);
    startListeningIncomingPackets(udpTransportLayer, chanel);

    chanel
        .connect(333, udpaddress)
        .thenCompose(v -> chanel.write(getContentToSend("Content34")))
        .get();
  }

  private static Bytes getContentToSend(String inputString) {
    byte[] byteArray = inputString.getBytes(StandardCharsets.UTF_8);
    ByteBuffer buffer = ByteBuffer.allocate(byteArray.length);
    buffer.put(byteArray);
    buffer.flip();
    System.out.println("Content to send:" + StandardCharsets.UTF_8.decode(buffer));
    return Bytes.of(buffer.array());
  }

  public static void startListeningIncomingPackets(
      UDPTransportLayer transportLayer, UTPClient utpClient) {
    CompletableFuture.runAsync(
        () -> {
          while (true) {
            if (utpClient.isAlive()) {
              //              try {
              //                utpClient.receivePacket(transportLayer.onPacketReceive());
              //              } catch (IOException e) {
              //                throw new RuntimeException(e);
              //              }
            }
          }
        });
  }
}
