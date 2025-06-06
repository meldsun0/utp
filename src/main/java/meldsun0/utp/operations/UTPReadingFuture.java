package meldsun0.utp.operations;

import meldsun0.utp.UTPClient;
import meldsun0.utp.UtpTimestampedPacketDTO;
import meldsun0.utp.algo.UtpAlgConfiguration;
import meldsun0.utp.data.MicroSecondsTimeStamp;
import meldsun0.utp.data.SelectiveAckHeaderExtension;
import meldsun0.utp.data.UtpPacket;
import meldsun0.utp.data.bytes.UnsignedTypesUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static meldsun0.utp.data.UtpPacketUtils.NO_EXTENSION;
import static meldsun0.utp.data.UtpPacketUtils.SELECTIVE_ACK;

public class UTPReadingFuture {

    private static final int PACKET_DIFF_WARP = 50000;
    public static final int TIMEOUT = 4000000;
    private int lastPayloadLength = UtpAlgConfiguration.MAX_PACKET_SIZE;
    private final ByteArrayOutputStream buffer;

    private final SkippedPacketBuffer skippedBuffer = new SkippedPacketBuffer();
    private volatile boolean graceFullInterrupt;
    private MicroSecondsTimeStamp timeStamper;
    private long totalPayloadLength = 0;
    private long lastPacketTimestamp;

    private long nowtimeStamp;
    private long lastPackedRecieved;
    private final long startReadingTimeStamp;
    private boolean gotLastPacket = false;
    // in case we ack every x-th packet, this is the counter.
    private int currentPackedAck = 0;

    private static final Logger LOG = LogManager.getLogger(UTPReadingFuture.class);
    private final UTPClient UTPClient;
    private final CompletableFuture<Bytes> readFuture;

    private final AtomicBoolean started = new AtomicBoolean(false);

    public UTPReadingFuture(UTPClient UTPClient, MicroSecondsTimeStamp timestamp) {
        this.UTPClient = UTPClient;
        this.buffer = new ByteArrayOutputStream();
        this.timeStamper = timestamp;
        this.startReadingTimeStamp = timestamp.timeStamp();
        this.readFuture = new CompletableFuture<>();
    }

    public CompletableFuture<Bytes> startReading(int startingSequenceNumber, ExecutorService executorService) {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("UTPReadingFuture has already been called.");
        }
        CompletableFuture.runAsync(
                () -> {
                    String connectionInfo = UTPClient.getConnectionsInfo();
                    Bytes firstPacket = Bytes.EMPTY;
                    skippedBuffer.setExpectedSequenceNumber(startingSequenceNumber);
                    try {
                        while (continueReading()) {
                            BlockingQueue<UtpTimestampedPacketDTO> queue = UTPClient.getQueue();
                            UtpTimestampedPacketDTO packetDTO =
                                    queue.poll(
                                            UtpAlgConfiguration.TIME_WAIT_AFTER_LAST_PACKET / 2, TimeUnit.MICROSECONDS);
                            nowtimeStamp = timeStamper.timeStamp();

                            if (packetDTO != null) {
                                currentPackedAck++;
                                lastPackedRecieved = packetDTO.stamp();
                                if (isLastPacket(packetDTO)) {
                                    gotLastPacket = true;
                                    lastPacketTimestamp = timeStamper.timeStamp();
                                    LOG.debug("{} Received the last packet.", connectionInfo);
                                }
                                // TODO FIX THIS!!!!
                                if ((startingSequenceNumber & 0xFFFF)
                                        == (packetDTO.utpPacket().getSequenceNumber() & 0xFFFF)) {
                                    firstPacket = Bytes.of(packetDTO.utpPacket().getPayload());
                                }

                                if (isPacketExpected(packetDTO.utpPacket())) {
                                    handleExpectedPacket(packetDTO);
                                } else {
                                    handleUnexpectedPacket(packetDTO);
                                }
                            }

                            if (ackThisPacket()) {
                                currentPackedAck = 0;
                            }

                            /*TODO: How to measure Rtt here for dynamic timeout limit?*/
                            if (isTimedOut()) {
                                if (!hasSkippedPackets()) {
                                    gotLastPacket = true;
                                    LOG.debug("{} Ending reading, no more incoming data", connectionInfo);
                                } else {
                                    LOG.debug("{} Timeout occurred with skipped packets.", connectionInfo);
                                    throw new IOException("Timeout occurred with skipped packets on " + connectionInfo);
                                }
                            }
                        }
                    } catch (IOException | InterruptedException | ArrayIndexOutOfBoundsException e) {
                        LOG.debug("Something went wrong during packet processing! on {}", connectionInfo);
                    } finally {
                        try {
                            if (!skippedBuffer.isEmpty()) {
                                LOG.debug("Flushing skipped packets on {}", connectionInfo);
//                Queue<UtpTimestampedPacketDTO> remaining = skippedBuffer.getAllUntilNextMissing2();
//                for (UtpTimestampedPacketDTO dto : remaining) {
//                  buffer.write(dto.utpPacket().getPayload());
//                  totalPayloadLength += dto.utpPacket().getPayload().length;
//                }
                            }
                            boolean successful = totalPayloadLength > 0 || gotLastPacket;
                            if (successful) {
                                readFuture.complete(Bytes.concatenate(firstPacket, Bytes.of(this.buffer.toByteArray())));
                            } else {
                                LOG.error("Read failed on {}: totalPayloadLength={}, gotLastPacket={}", connectionInfo, totalPayloadLength, gotLastPacket);
                                readFuture.completeExceptionally(new RuntimeException("Something went wrong!"));
                            }
                            LOG.info("Finished reading on {}. Total payload: {}", connectionInfo, totalPayloadLength);
                            UTPClient.stop();
                        } catch (Exception ex) {
                            LOG.debug("UTPReading failed {} on " + connectionInfo, ex.getMessage());
                            readFuture.completeExceptionally(ex);
                        }
                    }
                }, executorService);
        return this.readFuture;
    }

    private boolean isTimedOut() {
        // TODO: extract constants...
        /* time out after 4sec, when eof not reached */
        boolean timedOut = nowtimeStamp - lastPackedRecieved >= TIMEOUT;
        /* but if remote socket has not recieved synack yet, he will try to reconnect
         * await that aswell */
        boolean connectionReattemptAwaited = nowtimeStamp - startReadingTimeStamp >= TIMEOUT;
        return timedOut && connectionReattemptAwaited;
    }

    private boolean isLastPacket(UtpTimestampedPacketDTO timestampedPair) {
        return (timestampedPair.utpPacket().getWindowSize() & 0xFFFFFFFF) == 0; // TODO Check specs
    }

    private void handleExpectedPacket(UtpTimestampedPacketDTO timestampedPair) throws IOException {
        if (hasSkippedPackets()) {
            buffer.write(timestampedPair.utpPacket().getPayload());
            int payloadLength = timestampedPair.utpPacket().getPayload().length;
            lastPayloadLength = payloadLength;
            totalPayloadLength += payloadLength;
            Queue<UtpTimestampedPacketDTO> packets = skippedBuffer.getAllUntilNextMissing();
            int lastSeqNumber = 0;
            if (packets.isEmpty()) {
                lastSeqNumber = timestampedPair.utpPacket().getSequenceNumber() & 0xFFFF;
            }
            UtpPacket lastPacket = null;
            for (UtpTimestampedPacketDTO p : packets) {
                buffer.write(p.utpPacket().getPayload());
                payloadLength += p.utpPacket().getPayload().length;
                lastSeqNumber = p.utpPacket().getSequenceNumber() & 0xFFFF;
                lastPacket = p.utpPacket();
            }
            skippedBuffer.reindex(lastSeqNumber);
            UTPClient.setAckNumber(lastSeqNumber);
            // if still has skipped packets, need to selectively ack
            if (hasSkippedPackets()) {
                if (ackThisPacket()) {
                    //					log.debug("acking expected, had, still have");
                    SelectiveAckHeaderExtension headerExtension = skippedBuffer.createHeaderExtension();
                    UtpPacket packet =
                            UTPClient.buildSelectiveACK(
                                    headerExtension,
                                    getTimestampDifference(timestampedPair),
                                    getLeftSpaceInBuffer(),
                                    SELECTIVE_ACK);
                    UTPClient.sendPacket(packet);
                }

            } else {
                if (ackThisPacket()) {
                    UtpPacket ackPacket =
                            UTPClient.buildACKPacket(
                                    lastPacket, getTimestampDifference(timestampedPair), getLeftSpaceInBuffer());
                    UTPClient.sendPacket(ackPacket);
                }
            }
        } else {
            if (ackThisPacket()) {
                UtpPacket ackPacket =
                        UTPClient.buildACKPacket(
                                timestampedPair.utpPacket(),
                                getTimestampDifference(timestampedPair),
                                getLeftSpaceInBuffer());
                UTPClient.sendPacket(ackPacket);
            } else {
                UTPClient.setAckNumber(timestampedPair.utpPacket().getSequenceNumber() & 0xFFFF);
            }
            buffer.write(timestampedPair.utpPacket().getPayload());
            totalPayloadLength += timestampedPair.utpPacket().getPayload().length;
        }
    }

    private boolean ackThisPacket() {
        return currentPackedAck >= UtpAlgConfiguration.SKIP_PACKETS_UNTIL_ACK;
    }

    public long getLeftSpaceInBuffer() throws IOException {
        return (long) (skippedBuffer.getFreeSize()) * lastPayloadLength;
    }

    private int getTimestampDifference(UtpTimestampedPacketDTO timestampedPair) {
        return timeStamper.utpDifference(
                timestampedPair.utpTimeStamp(), timestampedPair.utpPacket().getTimestamp());
    }

    private void handleUnexpectedPacket(UtpTimestampedPacketDTO timestampedPair) throws IOException {
        int expected = getExpectedSeqNr();
        int receivedSeqNumber = timestampedPair.utpPacket().getSequenceNumber() & 0xFFFF;
        if (skippedBuffer.isEmpty()) {
            skippedBuffer.setExpectedSequenceNumber(expected);
        }
        // TODO: wrapping seq nr: expected can be 5 e.g.
        // but buffer can recieve 65xxx, which already has been acked, since seq numbers wrapped.
        // current implementation puts this wrongly into the buffer. it should go in the else block
        // possible fix: alreadyAcked = expected > seqNr || seqNr - expected > CONSTANT;
        boolean alreadyAcked =
                expected > receivedSeqNumber || receivedSeqNumber - expected > PACKET_DIFF_WARP;

        boolean saneSeqNr = expected == skippedBuffer.getExpectedSequenceNumber();
        //		log.debug("saneSeqNr: " + saneSeqNr + " alreadyAcked: " + alreadyAcked + " will ack: " +
        // ackThisPacket());
        if (saneSeqNr && !alreadyAcked) {
            skippedBuffer.bufferPacket(timestampedPair);
            // need to create header extension after the packet is put into the incomming buffer.
            SelectiveAckHeaderExtension headerExtension = skippedBuffer.createHeaderExtension();
            if (ackThisPacket()) {
                UtpPacket packet =
                        UTPClient.buildSelectiveACK(
                                headerExtension,
                                getTimestampDifference(timestampedPair),
                                getLeftSpaceInBuffer(),
                                SELECTIVE_ACK);
                UTPClient.sendPacket(packet);
            }
        } else if (ackThisPacket()) {
            SelectiveAckHeaderExtension headerExtension = skippedBuffer.createHeaderExtension();
            UtpPacket packet =
                    UTPClient.buildSelectiveACK(
                            headerExtension,
                            getTimestampDifference(timestampedPair),
                            getLeftSpaceInBuffer(),
                            NO_EXTENSION);
            UTPClient.sendPacket(packet);
        }
    }

    public boolean isPacketExpected(UtpPacket utpPacket) {
        int seqNumberFromPacket = utpPacket.getSequenceNumber() & 0xFFFF;
        return getExpectedSeqNr() == seqNumberFromPacket;
    }

    private int getExpectedSeqNr() {
        int ackNumber = UTPClient.getAckNumber();
        if (ackNumber == UnsignedTypesUtil.MAX_USHORT) {
            return 1;
        }

        return ackNumber + 1;
    }

    public void graceFullInterrupt() {
        graceFullInterrupt = true;

    }

    private boolean continueReading() {
        boolean shouldContinue = !graceFullInterrupt &&
                (!gotLastPacket ||           // Haven't finished reading
                        hasSkippedPackets()       // Still waiting for missing packets
                        //||  !timeAwaitedAfterLastPacket() // Still within wait window
                );
        if (!shouldContinue) {
            LOG.debug("Exiting read loop: graceFullInterrupt={}, gotLastPacket={}, hasSkippedPackets={}, timeAwaited={}",
                    graceFullInterrupt, gotLastPacket, hasSkippedPackets(), timeAwaitedAfterLastPacket());
        }
        return shouldContinue;
    }

    private boolean hasSkippedPackets() {
        return !skippedBuffer.isEmpty();
    }

    private boolean timeAwaitedAfterLastPacket() {
        return (timeStamper.timeStamp() - lastPacketTimestamp)
                > UtpAlgConfiguration.TIME_WAIT_AFTER_LAST_PACKET
                && gotLastPacket;
    }

    public boolean isAlive() {
        return this.readFuture.isDone();
    }
}
