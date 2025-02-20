package meldsun0.utp.message;

import meldsun0.utp.data.UtpPacket;

import static meldsun0.utp.data.UtpPacketUtils.*;
import static meldsun0.utp.data.bytes.UnsignedTypesUtil.*;

public class MessageUtil {

  public static UtpPacket buildACKMessage(
      int timeDifference,
      long advertisedWindow,
      int timestamp,
      long connectionIdSending,
      int ackNumber,
      byte firstExtension) {
    return UtpPacket.builder()
        .typeVersion(STATE)
        .firstExtension(firstExtension)
        .connectionId(longToUshort(connectionIdSending))
        .timestamp(timestamp)
        .timestampDifference(timeDifference)
        .windowSize(longToUint(advertisedWindow))
        .ackNumber(longToUshort(ackNumber))
        .build();
  }

  public static UtpPacket buildFINMessage(
      int timestamp, long connectionIdSending, int ackNumber, int sequenceNumber) {
    return UtpPacket.builder()
        .typeVersion(FIN)
        .connectionId(longToUshort(connectionIdSending))
        .timestamp(timestamp)
        .ackNumber(longToUshort(ackNumber))
        .sequenceNumber(longToUshort(sequenceNumber))
        .build();
  }

  public static UtpPacket buildDataMessage(
      int timestamp, long connectionIdSending, int ackNumber, int sequenceNumber) {
    return UtpPacket.builder()
        .typeVersion(DATA)
        .connectionId(longToUshort(connectionIdSending))
        .timestamp(timestamp)
        .ackNumber(longToUshort(ackNumber))
        .sequenceNumber(longToUshort(sequenceNumber))
        .build();
  }

  public static UtpPacket buildSYNMessage(int timestamp, long connectionId, long advertisedWindow, long seqNumber) {
    return UtpPacket.builder()
        .typeVersion(SYN)
        .sequenceNumber(longToUbyte(1)) //TODO FIX
        .timestampDifference(0)
        .windowSize(longToUint(advertisedWindow))
        .connectionId(longToUshort(connectionId))
        .timestamp(timestamp)
        .build();
  }
}
