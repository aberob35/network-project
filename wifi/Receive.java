package wifi;

import rf.RF;

import java.util.concurrent.ArrayBlockingQueue;

/**
 * Used for receiving transmissions and handling ACKs.
 *
 * @author Matthew Allen
 * @author Abram Robin
 * @author Caleb Puapuaga
 * @version 1.0
 */
public class Receive implements Runnable {

    // Fields

    // Important
    private RF rf;                                  // RF layer we are using
    private LinkLayer linkLayer;                    // Link layer we are using
    private ArrayBlockingQueue<byte[]> recvQueue;   // ArrayBlockingQueue of packets received
    private short ourMAC;                           // Our MAC that we are using as the source

    // Constants
    private final int DATA_TYPE = 0;
    private final int ACK_TYPE = 1;
    private final int BEACON_TYPE = 2;
    private final int BROADCAST_ADDR = -1;
    private final long RECV_FUDGE_FACTOR = 2500; // Fudge factor for receiving and deconstructing a beacon


    public Receive(LinkLayer linkLayer, RF rf, short ourMAC) {
        // Initialize the fields
        this.rf = rf;
        this.linkLayer = linkLayer;
        this.ourMAC = ourMAC;

        // Initialize the ArrayBlockingQueue
        recvQueue = new ArrayBlockingQueue<>(10);
    }

    /**
     * Run and receive packets
     */
    @Override
    public void run() {
        // Continuously monitor the RF layer to see if a packet can be received
        while (true) {
            // Get the packet from the head of the queue
            byte[] receivedPacket = rf.receive();

            // Create a new Packet object to examine the contents of the packet received
            // Deconstruct the byte[] and get its contents from the constructor
            Packet packet = new Packet(receivedPacket);

            // Check that the destination address in the received packet == ourMAC
            // Also check that the incoming packet isn't an ACK
            if (packet.getDestAddr() == ourMAC && packet.getPacketType() == DATA_TYPE) {
                // Handle the queue size at 4
                if (recvQueue.size() < 4) {
                    // ----- USED FOR DEBUGGING IF TURNED ON -----
                    if (linkLayer.isDebug()) {
                        linkLayer.getOutput().println("Queued incoming DATA packet with good CRC: " + packet.toString());
                    }

                    // Add the packet to the queue
                    recvQueue.add(receivedPacket);

                    // Construct and send an ACK packet back
                    Packet ack = new Packet(1, 0, packet.getDestAddr(), packet.getSourceAddr(), new byte[0], 0, packet.getSequenceNumber());

                    // Get the byte[] representation of this packet
                    byte[] ackPacket = ack.getPacket();

                    // Wait SIFS before sending an ACK back
                    try {
                        // Sleep for SIFS
                        Thread.sleep(RF.aSIFSTime);
                    }

                    // Something went wrong...
                    catch (Exception ignored) {
                        // But that's not my problem right now
                    }

                    // ----- USED FOR DEBUGGING IF TURNED ON -----
                    if (linkLayer.isDebug()) {
                        linkLayer.getOutput().println("Sending ACK back to " + ack.getDestAddr() + ": " + ack.toString());
                    }

                    // Transmit the ACK
                    rf.transmit(ackPacket);
                }
            }

            // Check that the destination address in the received packet == -1 for broadcast
            else if (packet.getDestAddr() == BROADCAST_ADDR && packet.getPacketType() != BEACON_TYPE) {
                // ----- USED FOR DEBUGGING IF TURNED ON -----
                if (linkLayer.isDebug()) {
                    linkLayer.getOutput().println("Queued incoming DATA packet with good CRC: " + packet.toString());
                    linkLayer.getOutput().println("Receive has blocked, awaiting data");
                }
                // Add the packet to the queue
                recvQueue.add(receivedPacket);

                // Don't send an ACK for a broadcast packet
            }

            // ACK for us, send to ACK queue
            else if (packet.getPacketType() == ACK_TYPE && packet.getDestAddr() == ourMAC) {
                // Add ACK to ack queue
                linkLayer.getAckQueue().add(packet);
            }

            // Got a DATA packet not for us
            else if (packet.getPacketType() == DATA_TYPE && packet.getDestAddr() != ourMAC) {
                // ----- USED FOR DEBUGGING IF TURNED ON -----
                if (linkLayer.isDebug()) {
                    linkLayer.getOutput().println("Got packet from " + packet.getSourceAddr() + " but it's not for us: " + packet.toString());
                }
            }

            // Got ACK packet not for us
            else if (packet.getPacketType() == ACK_TYPE && packet.getDestAddr() != ourMAC) {
                // ----- USED FOR DEBUGGING IF TURNED ON -----
                if (linkLayer.isDebug()) {
                    linkLayer.getOutput().println("Saw someone ACK for someone else: " + packet.toString());
                }
            }

            // Got a beacon packet
            else if (packet.getPacketType() == BEACON_TYPE && packet.getDestAddr() == BROADCAST_ADDR) {
                // ----- USED FOR DEBUGGING IF TURNED ON -----
                if (linkLayer.isDebug()) {
                    linkLayer.getOutput().println("Got a beacon frame  " + packet.toString());
                }

                long receivedTime = packet.convertToTime(); // The timestamp of the beacon when sent
                long adjustedTime = receivedTime - RECV_FUDGE_FACTOR; // Adjust for receiving and deconstructing the beacon

                // Adjust our local clock if adjusted time is ahead
                if (adjustedTime > linkLayer.localClock()) {
                    long temp = linkLayer.localClock();
                    long diff = adjustedTime - temp;

                    // Adjust the offset based on the adjusted time
                    linkLayer.setOffset(diff);

                    // ----- USED FOR DEBUGGING IF TURNED ON -----
                    if (linkLayer.isDebug()) {
                        linkLayer.getOutput().println("Advanced our clock by " + diff + " due to beacon:\n" +
                                "   incoming offset was " + adjustedTime + " vs our " + temp + ". Time is now " + linkLayer.localClock());
                    }
                }

                // Report that it was ignored
                else {
                    // ----- USED FOR DEBUGGING IF TURNED ON -----
                    if (linkLayer.isDebug()) {
                        linkLayer.getOutput().println("Ignored beacon: incoming timestamp was " + packet.convertToTime() + " vs our " + linkLayer.localClock());
                    }
                }
            }
        }
    }

    /**
     * Return the received packet queue.
     *
     * @return ArrayBlockingQueue for received packets
     */
    public ArrayBlockingQueue<byte[]> getRecvQueue() {
        return recvQueue;
    }
}
