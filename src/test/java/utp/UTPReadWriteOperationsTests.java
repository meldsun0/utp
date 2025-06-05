package utp;

import meldsun0.utp.UTPClient;
import meldsun0.utp.network.udp.UDPAddress;
import meldsun0.utp.network.udp.UDPTransportLayer;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class UTPReadWriteOperationsTests {


    @Test
    public void testWritingToAClient() throws ExecutionException, InterruptedException {
        var utpReaderExecutor = Executors.newVirtualThreadPerTaskExecutor();
        var utpWriterExecutor = Executors.newVirtualThreadPerTaskExecutor();

        var readerTransport = new UDPTransportLayer(124);
        var writerTransport = new UDPTransportLayer(123);

        var utpReader = new UTPClient(readerTransport);
        var utpWriter = new UTPClient(writerTransport);

        var readerAddress = new UDPAddress("localhost", 124);
        var writerAddress = new UDPAddress("localhost", 123);

        CompletableFuture<Bytes> receivedDataFuture = new CompletableFuture<>();

        utpReaderExecutor.submit(receivingListener(utpReader, readerTransport, writerAddress));
        utpWriterExecutor.submit(receivingListener(utpWriter, writerTransport, readerAddress));

        CompletableFuture<Void> readerReady = utpReader
                .startListening(111, writerAddress)
                .thenCompose(__ -> utpReader.read(utpReaderExecutor))
                .thenAccept(receivedDataFuture::complete)
                .exceptionally(ex -> {
                    ex.printStackTrace();
                    return null;
                });

        utpWriter.connect(111, readerAddress)
                .thenCompose(__ -> utpWriter.write(getContentToSend("xyz"), utpWriterExecutor))
                .exceptionally(ex -> {
                    ex.printStackTrace();
                    return null;
                })
                .get();

        Bytes received = receivedDataFuture.get();
        assertEquals(getContentToSend("xyz"), received, "Received content does not match expected value.");
        readerReady.get(); // Wait for reader to complete
    }

    private Runnable receivingListener(UTPClient utpClient, UDPTransportLayer transportLayer, UDPAddress remoteAddress){
        return () -> {
            while (true) {
                try {
                    var received = transportLayer.onPacketReceive();
                    utpClient.receivePacket(received, remoteAddress);
                } catch (SocketException e) {
                    break;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };
    }

    private static Bytes getContentToSend(String inputString) {
        byte[] byteArray = inputString.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(byteArray.length);
        buffer.put(byteArray);
        buffer.flip();
        System.out.println("Content to send:" + StandardCharsets.UTF_8.decode(buffer));
        return Bytes.of(buffer.array());
    }

}
