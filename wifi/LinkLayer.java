package wifi;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.zip.CRC32;

import rf.RF;

/**
 * Use this layer as a starting point for your project code.  See {@link Dot11Interface} for more
 * details on these routines.
 *
 * @author richards
 */
public class LinkLayer implements Dot11Interface {
    // Pre-existing fields
    private RF theRF;           // You'll need one of these eventually
    private short ourMAC;       // Our MAC address
    private PrintWriter output; // The output stream we'll write to

    // Threaded fields
    private Transmit transmitPacket; // Transmit object that shares an RF
    private Receive receivePacket; // Receive object that shares an RF

    // Data structure fields
    private ArrayBlockingQueue<Packet> ackQueue = new ArrayBlockingQueue<>(10); // ArrayBlockingQueue of ACK packets
    private HashMap<Short, Integer> seqNums = new HashMap<>(); // Keep track of the MAC address with the last sent sequence number

    // Fields determined by command window
    private boolean debug = false; // Used for debug command in GUI
    private boolean maxSlotSelect = false; // Used for maximum slot selection
    private int beaconInterval; // Used to transmit the beacons
    private boolean beaconDisabled = true; // Disables beacons if set to true

    // Others
    private volatile int status; // Status of the 802.11~ layer

    // Beacons & Timeouts
    private volatile long offset;
    private volatile long lastBeaconSentTime;


    /**
     * Constructor takes a MAC address and the PrintWriter to which our output will
     * be written.
     *
     * @param ourMAC MAC address
     * @param output Output stream associated with GUI
     */
    public LinkLayer(short ourMAC, PrintWriter output) {
        status = 0; // Link layer status set to 0

        offset = 0; // Initialize the offset to 0

        beaconInterval = 0; // Initialize beacon interval to 0

        // Initialize the pre-existing fields
        this.ourMAC = ourMAC;
        this.output = output;
        theRF = new RF(null, null);
        output.println("Link Layer initialized with MAC address " + ourMAC);

        // Construct a new Transmit object
        transmitPacket = new Transmit(this, theRF);

        // Construct a new Receive object
        receivePacket = new Receive(this, theRF, ourMAC);

        // Create a new sender thread to see if packets were placed on its queue
        Thread sender = new Thread(transmitPacket);

        // Create a new receiver thread to see if the RF layer has any incoming packets
        Thread receiver = new Thread(receivePacket);

        //Start the threads
        sender.start();
        receiver.start();

    }

    /**
     * Send method takes a destination, a buffer (array) of data, and the number
     * of bytes to send.  See docs for full description.
     *
     * @param dest MAC address to which data should be sent
     * @param data Buffer containing data
     * @param len  Number of bytes of data to send
     * @return The number of bytes accepted for transmission, or -1 on error.
     */
    public int send(short dest, byte[] data, int len) {
        // Handle the queue size at 4
        if (transmitPacket.getSendQueue().size() >= 4) {
            status = 5; // Set status to TX failed
            return 0; // Notify that the packet was dropped
        }
        // Layer above wants to send a packet
        // Check the destination address and see if it's in the HashMap
        // If it's not, the sequence number is 0, add it to the HashMap
        // If it is, set the sequence number to be get() + 1

        // Initialize the constructed packet
        byte[] constructedPacket = new byte[0];
        Packet p = new Packet(0, 0, ourMAC, dest, data, len, 0);

        // Destination address isn't in the HashMap
        if (!seqNums.containsKey(dest)) {
            // Put the destination address with a sequence number of 0 in the HashMap
            seqNums.put(dest, 0);

            // Build the packet up in the Packet class
            // This should take care of all the bit shifting required

            constructedPacket = p.getPacket();

            if (debug) {
                output.println("Queuing " + len + " bytes for " + dest);
            }
        }

        // The destination address is already in the HashMap
        // Increment it by 1
        else {
            // Increment the current sequence number
            int seqNum = seqNums.get(dest) + 1;

            // Update the sequence number in the HashMap
            seqNums.put(dest, seqNum);

            // Update the sequence number of the already existing packet
            p.setSequenceNumber(seqNum);
            constructedPacket = p.getPacket();
        }

        // Place the constructed packet on the sender's queue
        transmitPacket.getSendQueue().add(constructedPacket);

        // Return the amount of bytes accepted (-1 if there is an error)
        return len;
    }

    /**
     * Recv method blocks until data arrives, then writes it an address info into
     * the Transmission object.  See docs for full description.
     *
     * @param t A Transmission instance to which the incoming data and address information is written.
     * @return The number of bytes received, or -1 on error.
     */
    public int recv(Transmission t) {
        // Block until a packet is taken off the queue
        byte[] polledPacket = new byte[0];
        try {
            // There's something on the queue, take it off
            polledPacket = receivePacket.getRecvQueue().take();
        }

        // Some error happened at Runtime
        catch (Exception ignored) {
            // I don't really care what it was because hopefully it was out of my control
        }

        // There is a packet on the queue
        // Deconstruct the packet and extract its contents
        // Run the Packet constructor that basically deconstructs the packet
        // Do so by creating a new packet object
        // Pass in the head of the queue that is in the Receive class
        Packet packet = new Packet(polledPacket);

        // If the CRC is matching
        // Set everything to the Transmission object
        //if (packet.isMatchingCRC()) {
        // Set the buffer for Transmission by getting the data field of the packet
        t.setBuf(packet.getData());

        // Set the destination address in Transmission
        t.setDestAddr(packet.getDestAddr());

        // Set the source address in the Transmission
        t.setSourceAddr(packet.getSourceAddr());

        // Output that we received the bytes for the GUI
        output.println("Received " + packet.getData().length + " bytes");

        // Return the length of data
        // This is how many bytes we actually received
        status = 1; // Set the status of the layer to be safe
        return packet.getData().length;
        //}
    }

    /**
     * Passes command info to your link layer.  See docs for full description.
     */
    public int command(int cmd, int val) {
        // Make sure the command entered is a number
        try {
            int beaconTime = 0;
            switch (cmd) {
                // Print the current settings to the output stream
                case 0:
                    output.println("-------------- Commands and Settings -----------------");
                    output.println("Cmd #0: Display command options and current settings");
                    output.println("Cmd #1: Set debug level.  Currently at 0");
                    output.println("\tUse -1 for full debug output, 0 for no output");
                    output.println("Cmd #2: Set slot selection method.  Currently random");
                    output.println("\tUse 0 for random slot selection, any other value to use maxCW");
                    output.println("Cmd #3: Set beacon interval.  Currently at " + beaconTime + " seconds");
                    output.println("\tValue specifies seconds between the start of beacons; -1 disables");
                    output.println("------------------------------------------------------");
                    break;

                // Debug settings
                case 1:
                    // Let the debug messages print
                    if (val == -1) {
                        debug = true;
                        output.println("Setting debug to -1");
                    }

                    // Turn the debug messages off
                    if (val == 0) {
                        debug = false;
                    }
                    break;

                // Slot selection
                case 2:
                    // The user wants random slot selection
                    if (val == 0) {
                        // Set the maxCW to false
                        maxSlotSelect = false;
                    }

                    // The user want max slot selection
                    else {
                        // Set the maxCW to true
                        maxSlotSelect = true;
                    }
                    break;

                case 3:
                    // Disable beacons
                    if (val == -1) {
                        beaconDisabled = true;
                        output.println("Beacon frames will never be sent");
                    }

                    // Any other value 1+, send at that
                    else if (val > 0) {
                        // Set beacon disabling to false
                        beaconDisabled = false;

                        // Set the beacon interval to what was asked
                        beaconInterval = val * 1000;

                        // For above
                        beaconTime = val;

                        output.println("Beacon frames will be sent every " + val + " seconds");

                        sendInitialBeacon();

                    }
                    break;

                default:
                    output.println("Not a valid command");
                    break;
            }
        }

        // Ignore the exception caught
        catch (Exception ignored) {
            output.println("Not a valid command");
        }
        return 0;
    }

    public void setLastBeaconSentTime(long time) {
        lastBeaconSentTime = time;
    }

    /**
     * Get the output for the GUI.
     *
     * @return PrintWriter object to write output to
     */
    public PrintWriter getOutput() {
        return output;
    }

    /**
     * See if the GUI is in debug mode.
     *
     * @return true if debugging, false otherwise
     */
    public boolean isDebug() {
        return debug;
    }

    /**
     * Get the queue of ACK packets.
     *
     * @return ArrayBlockingQueue for ACK packets
     */
    public ArrayBlockingQueue<Packet> getAckQueue() {
        return ackQueue;
    }

    /**
     * Returns a current status code.  See docs for full description.
     *
     * @return Returns the current status of the 802.11~ layer
     */
    public int status() {
        output.println("LinkLayer: status value:");
        return status;
    }

    /**
     * See if the maxCW is selected.
     *
     * @return maxCW is selected if true, random otherwise
     */
    public boolean isMaxSlotSelect() {
        return maxSlotSelect;
    }

    /**
     * See if beacon packets are disabled.
     *
     * @return true if disabled, false otherwise
     */
    public boolean isBeaconDisabled() {
        return beaconDisabled;
    }

    /**
     * Get the current offset.
     *
     * @return current offset
     */
    public long getOffset() {
        return offset;
    }

    /**
     * Set the offset.
     *
     * @param offset current value to set the offset to
     */
    public void setOffset(long offset) {
        this.offset = offset;
    }

    /**
     * Get the local clock of this machine.
     *
     * @return local clock time
     */
    public long localClock() {
        return offset + theRF.clock();
    }

    /**
     * Create a beacon packet.
     *
     * @return Packet object of a beacon
     */
    public Packet createBeacon() {
        // IEEE practice is 8 bytes for time stamp
        // Add in the sender's fudge factor

        if (!seqNums.containsKey((short) -1)) {
            byte[] timeStamp = timeStampToArray(localClock() + transmitPacket.getSENDER_FUDGE_FACTOR());

            seqNums.put((short) -1, 0);

            // Create a new beacon packet containing the time stamp that is meant to be broadcast
            // Return it
            return new Packet(2, 0, ourMAC, (short) -1, timeStamp, timeStamp.length, 0);
        }

        // Not in HashMap
        else {
            int seqNumber = seqNums.get((short) -1);
            seqNumber++;

            byte[] timeStamp = timeStampToArray(localClock() + transmitPacket.getSENDER_FUDGE_FACTOR());

            seqNums.put((short) -1, seqNumber);

            // Create a new beacon packet containing the time stamp that is meant to be broadcast
            // Return it
            return new Packet(2, 0, ourMAC, (short) -1, timeStamp, timeStamp.length, seqNumber);
        }
    }

    /**
     * Turn the timestamp into an 8 byte array.
     *
     * @param time Time to convert
     * @return byte[] of the timestamp
     */
    private byte[] timeStampToArray(long time) {
        // Initialize new array
        byte[] timeStamp = new byte[8];

        // Bit shift
        timeStamp[0] = (byte) (time >> 56);
        timeStamp[1] = (byte) (time >> 48);
        timeStamp[2] = (byte) (time >> 40);
        timeStamp[3] = (byte) (time >> 32);
        timeStamp[4] = (byte) (time >> 24);
        timeStamp[5] = (byte) (time >> 16);
        timeStamp[6] = (byte) (time >> 8);
        timeStamp[7] = (byte) time;

        // Return it
        return timeStamp;
    }


    /**
     * See if it is time to send a beacon.
     *
     * @return True if it is time, false otherwise
     */
    public boolean isTimeToBeacon() {
        // Get our current time
        long currentTime = System.currentTimeMillis();

        // Time to send
        if ((currentTime - lastBeaconSentTime) >= beaconInterval) {
            return true;
        }

        // Not time to send
        else {
            return false;
        }
    }

    /**
     * Send an initial beacon packet.
     */
    private void sendInitialBeacon() {
        // Check that the channel isn't in use
        if (!theRF.inUse()) {
            // Create a beacon packet
            byte[] beacon = createBeacon().getPacket();

            // Add it to the queue since Transmit is currently blocking
            transmitPacket.getSendQueue().add(beacon);

            // Update the initial beacon sent time
            lastBeaconSentTime = System.currentTimeMillis();
        }

        // Otherwise, there is a transmission going on
        // Beacons will send once finished
    }

    /**
     * Get the beacon sending interval.
     *
     * @return Beacon sending interval
     */
    public long getBeaconTimeInterval() {
        return (long) beaconInterval / 1000;
    }

    /**
     * Set the status of the RF layer.
     *
     * @param status Status to set the RF layer to
     */
    public void setStatus(int status) {
        this.status = status;
    }
}
