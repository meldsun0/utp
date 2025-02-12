package meldsun0.utp.handlers;

import meldsun0.utp.Session;

import java.net.DatagramPacket;

public interface UTPMessageHandler<Message> {

  void handle(Session utpSession, DatagramPacket udpPacket);
}
