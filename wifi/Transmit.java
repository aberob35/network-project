package wifi;

import rf.RF;

import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Used for transmitting packets and waiting for ACKs.
 *
 * @author Matthew Allen
 * @author Abram Robin
 * @author Caleb Puapuaga
 * @version 1.0
 */
public class Transmit implements Runnable {

    /**
     * Private Enum class to help designate the waiting state of sending a packet
     */
    private enum State {
        AWAIT_PACKET,   // Waiting for incoming packet
        IDLE_DIFS_WAIT, // Network isn't busy
        BUSY_DIFS_WAIT, // Network is busy
        AWAIT_ACK,      // Waiting for ACK packets
        SLOT_WAIT       // Waiting until slot countdown is finished
    }

    // Fields

    // Important
    private RF rf;                                  // RF layer we are using
    private LinkLayer linkLayer;                    // Link layer we are using
    private Random random = new Random();           // Random object for random wait times
    private State fsmState;                         // State of the network
    private ArrayBlockingQueue<byte[]> sendQueue;   // ArrayBlockingQueue of packets to transmit

    // Timing
    private int difs;       // Wait time
    private int cwSlot;     // Upper bound slot wait time
    private int cwSlotRand; // Random slot wait time in the bounds

    // Other
    private boolean isBroadcast; // Determines if a packet is meant for broadcasting (ignore ACKs)
    private int retransmissionAttempts; // Number of times a packet was attempting to resend

    // Constants
    private final int DATA_TYPE = 0;
    private final int BEACON_TYPE = 2;
    private final int BROADCAST_ADDR = -1;
    private final long ACK_TX_TIME = 1113;
    private final long SENDER_FUDGE_FACTOR = 2100;


    /**
     * Create a new Transmit thread to transmit packets.
     *
     * @param linkLayer Link layer to use
     * @param rf        RF layer to use
     */
    public Transmit(LinkLayer linkLayer, RF rf) {
        // Initialize the fields
        this.rf = rf;
        this.linkLayer = linkLayer;

        // Set the waiting state to accept incoming packets
        fsmState = State.AWAIT_PACKET;

        // Initialize the ArrayBlockingQueue
        sendQueue = new ArrayBlockingQueue<>(10);

        // Set the DIFS wait time to this:
        difs = RF.aSIFSTime + (2 * RF.aSlotTime);

        // Set the wait slot to 0
        cwSlot = 0;

        // Initialize the other fields
        isBroadcast = false;
        retransmissionAttempts = 0;
    }

    /**
     * Run and transmit the packet.
     */
    @Override
    public void run() {
        // Initialize a packet taken off the queue
        byte[] polledPacket = new byte[0];

        // Initialize a null packet (basically)
        Packet packet = null;

        // Continuously monitor the state of the sending thread
        while (true) {

            // Go through the finite state model and all of its wonderful states...
            switch (fsmState) {
                case AWAIT_PACKET:
                    // Beacons are enabled
                    // It's time to broadcast a beacon
                    // Prioritize beacons over data
                    if (!linkLayer.isBeaconDisabled() && linkLayer.isTimeToBeacon()) {
                        // Simulate that the beacon is the byte[] for less code below
                        polledPacket = linkLayer.createBeacon().getPacket();
                    }

                    // Beacons are enabled
                    // It's NOT time to broadcast a beacon
                    // Sit and watch for a data packet for time until time to send beacon
                    else if (!linkLayer.isBeaconDisabled() && !linkLayer.isTimeToBeacon()) {
                        // Await for data to be placed on the queue
                        try {
                            // There's something on the queue, take it off
                            polledPacket = sendQueue.poll(linkLayer.getBeaconTimeInterval(), TimeUnit.SECONDS);
                        }

                        // Some error happened at Runtime
                        catch (Exception ignored) {
                            // I don't really care what it was because hopefully it was out of my control
                        }

                        // No packet was grabbed in time
                        // Transmit beacon
                        if (polledPacket == null) {
                            polledPacket = linkLayer.createBeacon().getPacket();
                        }
                    }

                    // Beacons are disabled
                    // Block until data is on the queue
                    else if (linkLayer.isBeaconDisabled()) {
                        // Await for data to be placed on the queue
                        try {
                            // There's something on the queue, take it off
                            polledPacket = sendQueue.take();
                        }

                        // Some error happened at Runtime
                        catch (Exception ignored) {
                            // I don't really care what it was because hopefully it was out of my control
                        }
                    }

                    // Set the retransmission attempts back to 0
                    retransmissionAttempts = 0;

                    // ----- USED FOR DEBUGGING IF TURNED ON -----
                    if (linkLayer.isDebug()) {
                        linkLayer.getOutput().println("Starting collision window at [0..3]");
                        linkLayer.getOutput().println("Moving to IDLE_DIFS_WAIT with pending DATA");
                    }

                    // Set the broadcast packet to false
                    isBroadcast = false;

                    // Determine if the packet to send is a broadcast packet
                    // Deconstruct the packet from the constructor
                    packet = new Packet(polledPacket);

                    // The packet was meant for broadcast, ignore for ACKS
                    if (packet.getDestAddr() == -1) {
                        // Set the broadcast packet to true
                        // Beacons should follow this too
                        isBroadcast = true;
                    }

                    // Channel is idle
                    if (!rf.inUse()) {
                        // Move to idle DIFS wait
                        fsmState = State.IDLE_DIFS_WAIT;
                    }

                    // Set the contention window to the minimum size (3)
                    cwSlot = RF.aCWmin;

                    // Channel is busy
                    if (rf.inUse()) {
                        // ----- USED FOR DEBUGGING IF TURNED ON -----
                        if (linkLayer.isDebug()) {
                            linkLayer.getOutput().println("Moving to BUSY_DIFS_WAIT with pending DATA");
                        }

                        // Move to busy DIFS wait
                        fsmState = State.BUSY_DIFS_WAIT;
                    }

                    break;

                case IDLE_DIFS_WAIT:
                    // ----- USED FOR DEBUGGING IF TURNED ON -----
                    if (linkLayer.isDebug()) {
                        linkLayer.getOutput().println("Idle waited until " + linkLayer.localClock());
                        linkLayer.getOutput().println("Transmitting DATA after simple DIFS wait at " + linkLayer.localClock());
                    }

                    // Check that the channel still isn't in use
                    if (!rf.inUse()) {
                        // Try to sleep the thread
                        sleepDIFS();
                    }

                    // Channel is idle
                    if (!rf.inUse()) {
                        // Free to transmit the packet
                        rf.transmit(polledPacket);

                        // Set the time for when the transmission ended
                        linkLayer.setLastBeaconSentTime(System.currentTimeMillis());

                        // The packet was broadcast
                        if (isBroadcast) {
                            // ----- USED FOR DEBUGGING IF TURNED ON -----
                            if (linkLayer.isDebug()) {
                                if (packet.getPacketType() == DATA_TYPE) {
                                    linkLayer.getOutput().println("Moving to AWAIT_PACKET after broadcasting DATA");
                                } else if (packet.getPacketType() == BEACON_TYPE) {
                                    linkLayer.getOutput().println("Moving to AWAIT_PACKET after broadcasting BEACON");
                                }
                            }


                            // Go back to the beginning
                            fsmState = State.AWAIT_PACKET;
                        }

                        // The packet was meant for a specific MAC
                        else {
                            // ----- USED FOR DEBUGGING IF TURNED ON -----
                            if (linkLayer.isDebug()) {
                                linkLayer.getOutput().println("Moving to AWAIT_ACK after sending DATA");
                            }

                            // Wait for an ACK for the packet that was just sent
                            fsmState = State.AWAIT_ACK;
                        }
                    }

                    // Channel is busy
                    if (rf.inUse()) {
                        // Move to busy DIFS wait
                        fsmState = State.BUSY_DIFS_WAIT;
                    }
                    break;

                case BUSY_DIFS_WAIT:
                    // ----- USED FOR DEBUGGING IF TURNED ON -----
                    if (linkLayer.isDebug()) {
                        linkLayer.getOutput().println("Waiting for DIFS to elapse after current Tx...");
                    }

                    // Wait for the current transmission to end
                    while (rf.inUse()) {
                        // Sleep the thread
                        sleepDIFS();
                    }

                    // Get new slot window
                    getNewSlotWindow();

                    // If the channel isn't in use, sleep for DIFS
                    if (!rf.inUse()) {
                        // Try to sleep the thread
                        sleepDIFS();

                        // ----- USED FOR DEBUGGING IF TURNED ON -----
                        if (linkLayer.isDebug()) {
                            linkLayer.getOutput().println("DIFS wait is over, starting slot countdown (" + cwSlotRand + ")");
                        }

                        // Move to slot wait to count down the slot time
                        fsmState = State.SLOT_WAIT;
                    }

                    // If the channel is in use, wait until the transmission ends
                    if (rf.inUse()) {
                        // Sleep DIFS
                        sleepDIFS();

                        // Try again
                        fsmState = State.BUSY_DIFS_WAIT;
                    }
                    break;

                case SLOT_WAIT:
                    // Do the slot count down
                    while (cwSlotRand > 0) {
                        try {
                            // Get the current time
                            long currentTime = System.currentTimeMillis();

                            // Get the next boundary time
                            long nextBoundary = 50 - (currentTime % 50);

                            // Pick the minimum between the new boundary or slot time
                            long newSlotTime = Math.min(nextBoundary, RF.aSlotTime);

                            // This should help with the 50ms boundary transmission

                            // Sleep the thread for the aSlotTime constant
                            Thread.sleep(newSlotTime);

                            // ----- USED FOR DEBUGGING IF TURNED ON -----
                            if (linkLayer.isDebug()) {
                                linkLayer.getOutput().println("Idle waited until " + linkLayer.localClock());
                            }

                            // If the channel suddenly becomes used
                            // Report interruption
                            if (rf.inUse()) {
                                // ----- USED FOR DEBUGGING IF TURNED ON -----
                                if (linkLayer.isDebug()) {
                                    linkLayer.getOutput().println("Moving BACK to BUSY_DIFS_WAIT since slot count was interrupted (" + cwSlotRand + " left)");
                                }

                                // Exit and go back to BUSY_DIFS_WAIT
                                fsmState = State.BUSY_DIFS_WAIT;
                            }

                            // Decrement the counter after waiting
                            if (nextBoundary <= RF.aSlotTime) {
                                // Count down the slot timer
                                cwSlotRand--;
                            }
                        } catch (Exception ignored) {
                            // Not my problem
                        }
                    }
                    // The channel is idle
                    if (!rf.inUse()) {
                        // The packet was broadcast
                        if (isBroadcast) {
                            // ----- USED FOR DEBUGGING IF TURNED ON -----
                            if (linkLayer.isDebug()) {
                                if (packet.getPacketType() == DATA_TYPE) {
                                    linkLayer.getOutput().println("Transmitting DATA after DIFS+SLOTs wait at " + linkLayer.localClock());
                                    linkLayer.getOutput().println("Moving to AWAIT_PACKET after broadcasting DATA");
                                } else if (packet.getPacketType() == BEACON_TYPE) {
                                    linkLayer.getOutput().println("Transmitting DATA after DIFS+SLOTs wait at " + linkLayer.localClock());
                                    linkLayer.getOutput().println("Moving to AWAIT_PACKET after broadcasting BEACON");
                                }
                            }

                            // Transmit the broadcast packet
                            rf.transmit(packet.getPacket());

                            linkLayer.setLastBeaconSentTime(System.currentTimeMillis());

                            // Go back to the beginning
                            fsmState = State.AWAIT_PACKET;
                        }

                        // The packet was meant for a specific MAC
                        else {
                            // ----- USED FOR DEBUGGING IF TURNED ON -----
                            if (linkLayer.isDebug()) {
                                linkLayer.getOutput().println("Transmitting DATA after DIFS+SLOTs wait at " + linkLayer.localClock());
                                linkLayer.getOutput().println("Moving to AWAIT_ACK after sending DATA");
                            }

                            // Transmit the packet
                            rf.transmit(packet.getPacket());

                            // Wait for an ACK for the packet that was just sent
                            fsmState = State.AWAIT_ACK;
                        }
                    }
                    break;

                case AWAIT_ACK:
                    // Create a packet to deconstruct the incoming packet
                    Packet ackFromQueue = null;

                    // Timeout = SIFS + ACK Tx Duration + (SlotTime * our slot number)
                    long timeout = RF.aSIFSTime + ACK_TX_TIME + ((long) cwSlotRand * RF.aSlotTime);

                    // Initialize ACK wait times
                    long startWaitTime = 0;
                    long endWaitTime = 0;
                    long expectedExpireTime;
                    long actualExpireTime = 0;

                    // Block until ACK packet arrives
                    try {
                        // Start a timer
                        startWaitTime = System.currentTimeMillis();
                        expectedExpireTime = timeout + startWaitTime;

                        // Look for ACK in time
                        ackFromQueue = linkLayer.getAckQueue().poll(timeout, TimeUnit.MILLISECONDS);

                        // End the timer
                        endWaitTime = System.currentTimeMillis();
                        actualExpireTime = endWaitTime - expectedExpireTime;

                    } catch (Exception ignored) {
                        // Ignore
                    }


                    //Get the total time it took to get an ACK back for our packet
                    long elapsedTime = endWaitTime - startWaitTime;


                    // The ACK was meant for us
                    if (ackFromQueue != null && ackFromQueue.getDestAddr() == packet.getSourceAddr()) {
                        // Set TX_DELIVERED
                        linkLayer.setStatus(4);

                        // ----- USED FOR DEBUGGING IF TURNED ON -----
                        if (linkLayer.isDebug()) {
                            // Get the timeout time - the elapsed time to receive an ack
                            long msTime = timeout - elapsedTime;

                            linkLayer.getOutput().println("Got a valid ACK: " + ackFromQueue.toString());
                            linkLayer.getOutput().println("ACK arrived " + msTime + " ms before timeout");
                            linkLayer.getOutput().println("Moving to AWAIT_PACKET after receiving valid ACK");
                        }

                        // Go back to waiting for packets
                        fsmState = State.AWAIT_PACKET;
                    }

                    // We need to retransmit
                    else {
                        // ----- USED FOR DEBUGGING IF TURNED ON -----
                        if (linkLayer.isDebug()) {
                            // Early ACK expiration
                            linkLayer.getOutput().println("Ack timer expired at " + linkLayer.localClock() + " ( " + actualExpireTime + " ms early)");

                        }
                        // Do a retransmission
                        if (retransmissionAttempts < RF.dot11RetryLimit) {
                            // First transmission
                            if (retransmissionAttempts == 0) {
                                cwSlot = RF.aCWmin;

                                // ----- USED FOR DEBUGGING IF TURNED ON -----
                                if (linkLayer.isDebug()) {
                                    linkLayer.getOutput().println("Starting collision window at [0..3]");
                                    linkLayer.getOutput().println("Moving to BUSY_DIFS_WAIT after ACK timeout.  (slotCount = " + cwSlotRand + ")");
                                }
                            }

                            // Multiple transmissions later
                            else {
                                // Double the contention window
                                doubleContentionWindow();

                                // ----- USED FOR DEBUGGING IF TURNED ON -----
                                if (linkLayer.isDebug()) {
                                    if (cwSlot != 31) {
                                        linkLayer.getOutput().println("Doubled collision window -- is now [0.." + (cwSlot + 1) + "]");
                                    } else {
                                        linkLayer.getOutput().println("Doubled collision window -- is now [0.." + (cwSlot) + "]");
                                    }
                                    linkLayer.getOutput().println("Moving to BUSY_DIFS_WAIT after ACK timeout.  (slotCount = " + cwSlotRand + ")");
                                }
                            }

                            // Set the retransmission bit for the packet to 1 and resend it
                            assert packet != null;
                            packet.setRetransmission(1);
                            retransmissionAttempts++;

                            // Move to busy difs wait
                            fsmState = State.BUSY_DIFS_WAIT;
                        }
                        // Retransmission limit reached, drop the packet
                        else {
                            // ----- USED FOR DEBUGGING IF TURNED ON -----
                            if (linkLayer.isDebug()) {
                                linkLayer.getOutput().println("Starting collision window at [0..3]");
                                linkLayer.getOutput().println("Moving to AWAIT_PACKET after exceeding retry limit");
                            }

                            // Set TX failed
                            linkLayer.setStatus(5);

                            // Go back to waiting for a new Data packet
                            fsmState = State.AWAIT_PACKET;
                        }

                    }


                    // If the packet send was a broadcast, move on
                    if (packet.getDestAddr() == BROADCAST_ADDR) {
                        // ----- USED FOR DEBUGGING IF TURNED ON -----
                        if (linkLayer.isDebug()) {
                            linkLayer.getOutput().println("Receive has blocked, awaiting data");
                        }
                        // Go back to waiting for packets
                        fsmState = State.AWAIT_PACKET;
                    }
                    break;

                default:
                    break;
            }
        }
    }

    /**
     * Double the contention window.
     */
    private void doubleContentionWindow() {
        // Double the contention window
        cwSlot = Math.min(cwSlot * 2, RF.aCWmax); // This should keep the slot window from exceeding 31

    }

    /**
     * Get a new random slot.
     */
    private void getNewSlotWindow() {
        // User wants max slot available
        if (linkLayer.isMaxSlotSelect()) {
            // Set = to max slot currently
            cwSlotRand = cwSlot;
        }

        // Otherwise, select a random slot window
        else {
            // Get a random slot time in the window
            cwSlotRand = random.nextInt(cwSlot + 1);
        }
    }

    /**
     * Sleep threads before waking up.
     * Hopefully this works for the 50ms boundary. Math was weird.
     */
    private void sleepDIFS() {
        // Try to sleep the thread
        try {
            // Get the current time
            long currentTime = System.currentTimeMillis();

            // Get the next boundary
            long nextBoundary = 50 - (currentTime % 50);

            // Calculate the new sleep time
            long newDIFS = nextBoundary + difs;

            // This should help with the 50ms boundary

            // Sleep the thread for DIFS
            Thread.sleep(newDIFS);
        }

        // Catch the exception
        catch (Exception ignored) {
            // Not my problem for now
        }
    }

    /**
     * Get the queue used to send the packets.
     *
     * @return ArrayBlockingQueue containing the packets to be sent if not empty
     */
    public ArrayBlockingQueue<byte[]> getSendQueue() {
        return sendQueue;
    }

    /**
     * Get the fudge factor for constructing and sending a beacon packet.
     * @return fudge factor for constructing and sending a beacon packet
     */
    public long getSENDER_FUDGE_FACTOR() {
        return SENDER_FUDGE_FACTOR;
    }

}
