# utp - Micro Transport Protocol for Java
[![GitHub License](https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square)]()
[![Discord](https://img.shields.io/badge/Chat-on%20Discord-%235865F2?logo=discord&logoColor=white)](https://discord.com/channels/890617081744220180/1301231225276465152)

This library is based on [uTP][utp] and it was originally forked from [Tribler/utp4j](https://github.com/Tribler/utp4j).


# UTPClient Usage Example over UDP

This example demonstrates how to send and receive data between two local uTP clients over UDP using `UTPClient`.

---
### Step 1: Initialize Executors and UDP Transport Layers

```java
        var readerExecutor = Executors.newVirtualThreadPerTaskExecutor();
        var writerExecutor = Executors.newVirtualThreadPerTaskExecutor();

        var readerTransport = new UDPTransportLayer(124);
        var writerTransport = new UDPTransportLayer(123);
```

> Sets up virtual thread executors for concurrent I/O operations and initializes UDP transport layers on different ports for the sender and receiver.

---

### Step 2: Create uTP Clients and Define Peer Addresses

```java
        var utpReader = new UTPClient(readerTransport);
        var utpWriter = new UTPClient(writerTransport);

        var readerAddress = new UDPAddress("localhost", 124);
        var writerAddress = new UDPAddress("localhost", 123);
```

> Creates two `UTPClient` instances — one for receiving, one for sending — and defines their respective socket addresses.

---

### Step 3: Start Listening Threads for Incoming Packets

```java
        readerExecutor.submit(receivingListener(utpReader, readerTransport, writerAddress));
        writerExecutor.submit(receivingListener(utpWriter, writerTransport, readerAddress));
```

> Starts background threads that continuously listen for incoming UDP packets and pass them to the `UTPClient`.

---

### Step 4: Prepare Receiver to Accept and Read Incoming Data

```java
        CompletableFuture<Bytes> receivedFuture = new CompletableFuture<>();
        utpReader.startListening(111, writerAddress)
            .thenCompose(__ -> utpReader.read(readerExecutor))
            .thenAccept(receivedFuture::complete);
```

> Configures the reader to listen on channel `111` and asynchronously read data into a future.

---

### Step 5: Connect Sender and Send the Message

```java
        utpWriter.connect(111, readerAddress)
            .thenCompose(__ -> utpWriter.write(getContentToSend("Hello from uTP!"), writerExecutor))
            .get();
```

> The sender connects to the receiver and writes a UTF-8 message. The `get()` call blocks until sending is complete.

---

### Step 6: Wait for the Receiver and Print the Message

```java
        Bytes received = receivedFuture.get();
        System.out.println("✅ Received: " + new String(received.toArray(), StandardCharsets.UTF_8));
    }
```

> The receiver awaits incoming data and prints it to the console once received.

---

### Helper Methods

```java
    private static Runnable receivingListener(UTPClient utpClient, UDPTransportLayer transport, UDPAddress remoteAddress) {
        return () -> {
            while (true) {
                try {
                    var packet = transport.onPacketReceive();
                    utpClient.receivePacket(packet, remoteAddress);
                } catch (SocketException e) {
                    break;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };
    }

    private static Bytes getContentToSend(String input) {
        byte[] byteArray = input.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.wrap(byteArray);
        return Bytes.of(buffer.array());
    }
}
```

> - `receivingListener(...)`: Listens for incoming UDP packets and passes them to the uTP client.
> - `getContentToSend(...)`: Converts a string into a `Bytes` object suitable for transmission.

---

## Expected Output

```bash
Received: Hello from uTP!
```
---

## Notes

- There are two interfaces that need to be implemented before using UTP for reading or writing operations:
  - TransportLayer:
    - You define how a packet will be sent over the wire by receiving a UtpPacket and RemoteAddress
    - You define what to do once the transfer has finished.
  - TransportAddress:
    - You define your own RemoteAddress needed to be sent by the TransportLayer previously defined.

---

## Current Flaws
* Closing connection while sending/reading is not yet handled good enough
* High CPU consumption
* Probably some minor bugs.
* The uTP reference implementation deviates from the uTP specification on the initialization of the `ack_nr` when receiving the `ACK` of a `SYN` packet. The reference implementation [initializes](https://github.com/bittorrent/libutp/blob/master/utp_internal.cpp#L1874) this as `c.ack_nr = pkt.seq_nr - 1` while the specification indicates `c.ack_nr = pkt.seq_nr`. This uTP specifications follows the uTP reference implementation: `c.ack_nr = pkt.seq_nr - 1`.



## License
utp is licensed under the Apache 2.0 [license]. 
