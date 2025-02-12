package meldsun0.utp.operations;

import meldsun0.utp.UTPClient;
import meldsun0.utp.UtpTimestampedPacketDTO;
import meldsun0.utp.algo.UtpAlgorithm;
import meldsun0.utp.data.MicroSecondsTimeStamp;
import meldsun0.utp.data.UtpPacket;
import meldsun0.utp.data.bytes.UnsignedTypesUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class UTPWritingFuture {

  private static final Logger LOG = LogManager.getLogger(UTPWritingFuture.class);

  private final ByteBuffer buffer;
  private volatile boolean graceFullInterrupt;
  private final UTPClient utpClient;
  private final UtpAlgorithm algorithm;
  private final MicroSecondsTimeStamp timeStamper;
  private CompletableFuture<Void> writerFuture;

  public UTPWritingFuture(
      UTPClient utpClient, ByteBuffer buffer, MicroSecondsTimeStamp timeStamper) {
    this.buffer = buffer;
    this.utpClient = utpClient;
    this.timeStamper = timeStamper;
    this.algorithm = new UtpAlgorithm(timeStamper);
    this.writerFuture = new CompletableFuture<>();
  }

  public CompletableFuture<Void> startWriting() {
    CompletableFuture.runAsync(
        () -> {
          boolean successfull = false;
          try {
            initializeAlgorithm();
            buffer.flip();
            while (continueSending()) {
              if (!processAcknowledgements()) {
                LOG.debug("Graceful interrupt due to lack of acknowledgements.");
                break;
              }
              resendPendingPackets();

              if (algorithm.isTimedOut()) {
                LOG.debug("Timed out. Stopping transmission.");
                break;
              }
              sendNextPackets();
            }
            successfull = true;
          } catch (IOException exp) {
            LOG.debug("Something went wrong!");
          } finally {
            finalizeTransmission(successfull);
          }
        });
    return writerFuture;
  }

  public void graceFullInterrupt() {
    if (this.isAlive()) {
      graceFullInterrupt = true;
    }
  }

  private void initializeAlgorithm() {
    algorithm.initiateAckPosition(utpClient.getSequenceNumber());
    algorithm.setTimeStamper(timeStamper);
    algorithm.setByteBuffer(buffer);
  }

  private void finalizeTransmission(boolean successful) {
    algorithm.end(buffer.position(), successful);
    if (successful) {
      writerFuture.complete(null);
    } else {
      writerFuture.completeExceptionally(new RuntimeException("Something went wrong!"));
    }
    utpClient.sendPacketFinPacket();
    utpClient.stop();
  }

  private void sendNextPackets() throws IOException {
    while (algorithm.canSendNextPacket() && buffer.hasRemaining()) {
      UtpPacket utpPacket = utpClient.buildDataPacket();
      utpClient.sendPacket(this.buildNextPacket(utpPacket));
    }
  }

  private void resendPendingPackets() throws IOException {
    Queue<UtpPacket> packetsToResend = algorithm.getPacketsToResend();
    packetsToResend.forEach(
        packet -> {
          try {
            utpClient.sendPacket(packet);

          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }

  private boolean processAcknowledgements() {
    BlockingQueue<UtpTimestampedPacketDTO> packetQueue = utpClient.getQueue();
    long waitingTimeMicros = algorithm.getWaitingTimeMicroSeconds();
    try {
      UtpTimestampedPacketDTO packet = packetQueue.poll(waitingTimeMicros, TimeUnit.MICROSECONDS);
      while (packet != null) {
        algorithm.ackRecieved(packet);
        algorithm.removeAcked();
        packet = packetQueue.poll();
      }
      return true;
    } catch (InterruptedException e) {
      return false;
    }
  }

  private UtpPacket buildNextPacket(UtpPacket utpPacket) {
    int packetSize = Math.min(algorithm.sizeOfNextPacket(), buffer.remaining());
    packetSize = Math.min(packetSize, 800);
    byte[] payload = new byte[packetSize];
    buffer.get(payload);
    utpPacket.setPayload(payload);
    // Calculate remaining buffer size, capped at MAX_UINT
    int leftInBuffer = (int) Math.min(buffer.remaining(), UnsignedTypesUtil.MAX_UINT & 0xFFFFFFFF);
    utpPacket.setWindowSize(leftInBuffer);
    algorithm.markPacketOnfly(utpPacket); // Mark the packet as on the fly
    return utpPacket;
  }

  private boolean continueSending() {
    return !graceFullInterrupt && !allPacketsAckedSendAndAcked();
  }

  private boolean allPacketsAckedSendAndAcked() {
    //		return finSend && algorithm.areAllPacketsAcked() && !buffer.hasRemaining();
    return algorithm.areAllPacketsAcked() && !buffer.hasRemaining();
  }

  public boolean isAlive() {
    return this.writerFuture.isDone();
  }
}
