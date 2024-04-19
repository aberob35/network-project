package wifi;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Used for constructing and deconstructing packets.
 *
 * @author Matthew Allen
 * @author Abram Robin
 * @author Caleb Puapuaga
 * @version 1.0
 */
public class Packet {

    private CRC32 crcCalculator = new CRC32();// Used to calculate CRCs
    private byte[] packet; // Byte array representation of this packet
    private int retransmission; // Retransmission bit of the packet
    private short sourceAddr, destAddr; // MAC address for ourMAC(source) and the destination(dest)
    private byte[] data; // Byte representation of the data sent in the packet
    private int len; // Length at which we can transmit data
    private int sequenceNumber; // Sequence number of this packet
    private int crcNum; // Long representation of the CRC
    private long crcNumDeconstructed; // The CRC from the deconstructed packet
    private byte[] crcArr = new byte[4]; // Byte array representation of the CRC
    private int packetType; // Type of packet this is
    private boolean isMatchingCRC; // Determines if a CRC matches
    private final int DATA_TYPE = 0;
    private final int BEACON_TYPE = 2;

    /**
     * Construct a packet to transmit.
     *
     * @param packetType     type of packet to transmit
     * @param retryBit       1 to retransmit, 0 to not
     * @param ourMAC         Source MAC address
     * @param dest           Destination MAC address
     * @param data           byte[] of data to send in the packet
     * @param len            The length of data that can be transmitted
     * @param sequenceNumber Sequence number of the packet
     */
    public Packet(int packetType, int retryBit, short ourMAC, short dest, byte[] data, int len, int sequenceNumber) {
        // Assign all the local variables
        this.packetType = packetType;
        this.sequenceNumber = sequenceNumber;
        this.sourceAddr = ourMAC;
        this.destAddr = dest;

        // If len exceeds the size of the byte array, send as many bytes as data contains
        if (len > data.length) {
            // Update len to be the size of the byte array
            this.len = data.length;
        }

        // Construct the frame to transmit

        // A packet will be the size of 10 + the size of the data byte array
        // 2 bytes for control (0-1)
        // 2 bytes for destination address (2-3)
        // 2 bytes for source address (4-5)
        // 0-2038 bytes for the data byte array (Bytes len)
        // 4 bytes for the CRC (Bytes len+4)
        packet = new byte[data.length + 10];

        // packet[0] & packet[1] handle the control section
		/*
		Control bytes:
		3 bits: Frame type (data >> 000, ACK >> 001, beacon >> 010, CTS >> 100, RTS >> 101)
		1 bit: Retransmit if 1, don't if 0
		12 bits: Sequence number (start at 0, wrap back to 0 after (2^12)-1)
		 */

        // Since 4 bits of the sequence number lie in the first byte (thanks, Brad)
        // There needs to be some bit shifting (thanks again, Brad)

        // Retransmission bit
        retransmission = retryBit;

        // Checkpoint #2 specifics
        // Data packet only
        // Byte 1 = 3 bits for frame, 1 bit for transmission, 4 bits of sequenceNum
        // Byte 2 = remaining 8 bits of sequenceNum

        // Bit-shifting conversions
        // Frame: Left-shift by 5 bits
        // Retransmit: Left-shift by 4 bits
        // Sequence Number: Right-shift by 8, bitwise and by 0x0F
        byte frame = (byte) (packetType << 5);
        byte retry = (byte) (retryBit << 4);
        byte seqNum = (byte) ((sequenceNumber >> 8) & 0x0F);

        // Add each bit-shifted byte into the crc?

        // Bitwise or each part
        packet[0] = (byte) (frame | retry | seqNum);

        // Bitwise and the remainder of the sequence number by 0xFF
        packet[1] = (byte) (sequenceNumber & 0xFF);

        // Update the control header into the CRC
        crcCalculator.update(packet[0]);
        crcCalculator.update(packet[1]);

        // packet[2] & packet[3] support the 2 byte MAC address
        // Follow the protocol from the TimeServe project:
        // Do a bitwise shift (to the right) on the MAC address to store in the first two array slots
        // Do a bitwise and 0xFF to treat the value as an unsigned byte
        // Shift by 8-bits (1 byte), bitwise and by 0xFF
        packet[2] = (byte) ((dest >> 8) & 0xFF); // Lower bound of the MAC address
        packet[3] = (byte) (dest & 0xFF); // Upper bound of the MAC address

        // Update the destination address into the CRC
        crcCalculator.update(packet[2]);
        crcCalculator.update(packet[3]);

        // packet[4] & packet[5] support the 2 byte MAC address
        // Follow the protocol from the TimeServe project:
        // Do a bitwise shift (to the right) on the MAC address to store in the first two array slots
        // Do a bitwise and 0xFF to treat the value as an unsigned byte
        // Shift by 8-bits (1 byte), bitwise and by 0xFF
        packet[4] = (byte) ((ourMAC >> 8) & 0xFF); // Lower bound of the MAC address
        packet[5] = (byte) (ourMAC & 0xFF); // Upper bound of the MAC address

        // Update the source address into the CRC
        crcCalculator.update(packet[4]);
        crcCalculator.update(packet[5]);

        if (packetType == DATA_TYPE || packetType == BEACON_TYPE) {
            // Copy what can be in data[len] into packet[6] -> packet[6+len]
            // This allows flow control of the data that can be sent in  the packet
            for (int i = 6, j = 0; i < len + 6 && j < len; i++, j++) {
                // Copy data[j] into packet[i]
                packet[i] = data[j];
                // Add the updated data byte[] into the CRC
                crcCalculator.update(data[j]);
            }

            this.data = data;
        }

        // Store the new CRC value
        crcCalculator = new CRC32();
        crcCalculator.update(packet, 0, packet.length - 4);
        crcNum = (int) crcCalculator.getValue();

        // Bit-shift by 24, 16, 8 for 0, 1, 2
        // Bitwise-and all by 0XFF
        crcArr[0] = (byte) ((crcNum >> 24) & 0XFF);
        crcArr[1] = (byte) ((crcNum >> 16) & 0XFF);
        crcArr[2] = (byte) ((crcNum >> 8) & 0XFF);
        crcArr[3] = (byte) (crcNum & 0XFF);

        // Add the CRC to the packet
        packet[6 + data.length] = crcArr[0];
        packet[6 + data.length + 1] = crcArr[1];
        packet[6 + data.length + 2] = crcArr[2];
        packet[6 + data.length + 3] = crcArr[3];
    }

    /**
     * Take in a byte array and extract its contents from a packet.
     *
     * @param bytePacket Packet to deconstruct
     */
    public Packet(byte[] bytePacket) {
        packet = bytePacket;

        // We know how a packet is structured
        // Control: Bytes 1 & 2 sit in packet[0] & packet[1]
        // Dest Addr: Bytes 3 & 4 sit in packet[2] & packet[3]
        // Source Addr: Bytes 5 & 6 sit in packet[4] & packet[5]
        // CRC: Last 4 bytes packet[packet.length-1,2,3,4]
        // Data sits in between packet[6] & packet[packet.length-5]

        // Deconstruct the first two bytes to be the control
        // 3 bits for frame type
        // 1 bit for retry
        // 12 bits for sequence number

        // Convert the packet back to a frame by shifting the byte back right 5 bits and bitwise and by 0x07
        packetType = (packet[0] >> 5) & 0x07;

        // Update the frame into the CRC
        //crcCalculator.update(frame);

        // Convert the packet back to a retry bit by shifting the byte back right by 4 bits and bitwise and by 0x01
        retransmission = (packet[0] >> 4) & 0x01;

        // Update the retry bit into the CRC
        //crcCalculator.update(retry);

        // Need to do some more for getting the sequence number (thanks, Brad)
        // Bitwise and by 0x0F and then shift left by 8
        int firstPart = (packet[0] & 0x0F) << 8;

        // Bitwise and by 0xFF
        int secondPart = (packet[1] & 0xFF);

        // Combine the two to get the sequence number
        // Bitwise or the two
        sequenceNumber = firstPart | secondPart;

        // Update the sequence number into the CRC
        //crcCalculator.update(sequenceNum);

        // Update the control header into the CRC
        crcCalculator.update(packet[0]);
        crcCalculator.update(packet[1]);

        // Get the destination address
        // Bitwise and by 0xFF and shifting left 8 bits
        // Bitwise and by 0xFF
        // Bitwise or the two results
        destAddr = (short) (((packet[2] & 0xFF) << 8) | (packet[3] & 0xFF));

        // Update the destination address into the CRC
        crcCalculator.update(packet[2]);
        crcCalculator.update(packet[3]);

        // Get the destination address
        // Bitwise and by 0xFF and shifting left 8 bits
        // Bitwise and by 0xFF
        // Bitwise or the two results
        sourceAddr = (short) (((packet[4] & 0xFF) << 8) | (packet[5] & 0xFF));

        // Update the source address into the CRC
        crcCalculator.update(packet[4]);
        crcCalculator.update(packet[5]);

        if (packetType == DATA_TYPE || packetType == BEACON_TYPE) {
            // Figure out the length for data
            int dataLen = bytePacket.length - 10;

            // There is data
            if (dataLen > 0) {
                // Set the data array to be the packet length - 10
                // Subtract 10 for all the predefined data
                data = new byte[dataLen]; // Brad says no -10, but no -10 is making it sad :(

                len = dataLen;

                // Copy the data in packet to data
                for (int i = 0; i < data.length; i++) {
                    // Copy the remaining parts of packet[] to data[]
                    data[i] = packet[6 + i];
                    // Add the updated data byte[] into the CRC
                    crcCalculator.update(data[i]);
                }
            }

            // There is no data
            else {
                // Create empty data array
                data = new byte[0];

                len = 0;

                // Add the updated data byte[] into the CRC
                crcCalculator.update(data);
            }
        }


        // Not a data packet
        else {
            // Create empty data array
            data = new byte[0];

            len = 0;

            // Add the updated data byte[] into the CRC
            crcCalculator.update(data);
        }

        // Store the new CRC value
        crcNum = (int) crcCalculator.getValue();

        // Get the deconstructed CRC from this packet
        byte[] deconstructedCRCArr = new byte[4];
        deconstructedCRCArr[0] = packet[6 + data.length];
        deconstructedCRCArr[1] = packet[6 + data.length + 1];
        deconstructedCRCArr[2] = packet[6 + data.length + 2];
        deconstructedCRCArr[3] = packet[6 + data.length + 3];

        // Set that value found
        crcNumDeconstructed = deconstructCRC(deconstructedCRCArr);

        // Set the matching state of the deconstructed CRC and calculated CRC
        if (crcNumDeconstructed == crcNum) {
            isMatchingCRC = true;
        }

        // They're not equal
        else {
            isMatchingCRC = false;
        }
    }

    private long deconstructCRC(byte[] fullCRC) {
        // Reverse everything done to calculate the CRC
        long first = (fullCRC[0] & 0XFFL) << 24;
        long second = (fullCRC[1] & 0XFFL) << 16;
        long third = (fullCRC[2] & 0XFFL) << 8;
        long last = (fullCRC[3] & 0XFFL);

        // Bitwise-Or each part and return it
        return first | second | third | last;
    }

    /**
     * Get the toString format of this packet.
     *
     * @return String representation of this packet
     */
    @Override
    public String toString() {
        // Store the String
        String s = "<";

        // Get the proper frame type
        switch (packetType) { // IDE recommended "enhanced switch statement" with ->
            case 0 ->
                // Data frame
                    s += "DATA ";
            case 1 ->
                // ACK
                    s += "ACK ";
            case 2 ->
                // Beacon
                    s += "BEACON ";
            case 4 ->
                // CTS
                    s += "CTS ";
            case 5 ->
                // RTS
                    s += "RTS ";
            default ->
                // Error
                    s += "UNKNOWN ";
        }

        // Attach the retry bit, sequence number, destination address, and source address
        s += retransmission + " " + sequenceNumber + " " + sourceAddr + " -> " + destAddr + " [";

        if (packetType == BEACON_TYPE) {
            if (data != null && data.length == 8) {
                s += "\"" + convertToTime() + "\"]";
            }
        }

        if (data == null) {
            s += "] ";
        }


        // Convert data to a String
        else if (data.length > 0 && packetType == DATA_TYPE) {
            // Convert the byte array of data into a String
            String temp = new String(data, StandardCharsets.UTF_8);

            s += "\"" + temp + "\"] ";
        }

        // Attach an ending bracket and the CRC
        s += "(" + crcNum + ")>";

        // Return the packet
        return s;
    }

    /**
     * Return the data of this packet.
     *
     * @return byte[] of data
     */
    public byte[] getData() {
        return data;
    }

    /**
     * Return the byte[] representation of the CRC for this packet
     *
     * @return byte[] representation of the CRC
     */
    public byte[] getCrcArr() {
        return crcArr;
    }

    /**
     * Return the byte[] representation of this packet.
     *
     * @return byte[] representation of this packet
     */
    public byte[] getPacket() {
        return packet;
    }

    /**
     * Return the source address of this packet.
     *
     * @return short representing the source MAC address
     */
    public short getSourceAddr() {
        return sourceAddr;
    }

    /**
     * Return the destination address of this packet.
     *
     * @return short representing the destination MAC address
     */
    public short getDestAddr() {
        return destAddr;
    }

    /**
     * Return the retransmission state of this packet.
     *
     * @return 0 for no retransmission, 1 for retransmission
     */
    public int getRetransmission() {
        return retransmission;
    }

    /**
     * Return the amount of bytes to accept in the transmission of data.
     *
     * @return amount of bytes to accept in the transmission of data
     */
    public int getLen() {
        return len;
    }

    /**
     * Return the sequence number of this packet.
     *
     * @return sequence number of this packet
     */
    public int getSequenceNumber() {
        return sequenceNumber;
    }

    /**
     * Return the type of packet that this is.
     *
     * @return type of packet this is
     */
    public int getPacketType() {
        return packetType;
    }

    /**
     * Return true if the CRCs match, false otherwise.
     *
     * @return true if the CRCs match, false otherwise
     */
    public boolean isMatchingCRC() {
        return isMatchingCRC;
    }


    /**
     * Set the data byte[] for this packet.
     *
     * @param d byte[] to set data to
     */
    public void setData(byte[] d) {
        data = d;
    }

    /**
     * Set the crc byte[] for this packet.
     *
     * @param c byte[] to set the CRC byte[] to
     */
    public void setCrcArr(byte[] c) {
        crcArr = c;
    }

    /**
     * Set the packet byte[] for this packet.
     *
     * @param p byte[] to set the packet byte[] to
     */
    public void setPacket(byte[] p) {
        packet = p;
    }

    /**
     * Set the source address for this packet.
     *
     * @param s short MAC address to set the source address to
     */
    public void setSourceAddr(short s) {
        sourceAddr = s;
    }

    /**
     * Set the destination address for this packet.
     *
     * @param d short MAC address to set the destination address to
     */
    public void setDestAddr(short d) {
        destAddr = d;
    }

    /**
     * Set the retransmission bit of this packet.
     *
     * @param r bit to set the retransmission to
     */
    public void setRetransmission(int r) {
        retransmission = r;
    }

    /**
     * Set the length of data that can be accepted.
     *
     * @param len length of data that can be accepted
     */
    public void setLen(int len) {
        this.len = len;
    }

    /**
     * Set the sequence number of this packet.
     *
     * @param s sequence number to set
     */
    public void setSequenceNumber(int s) {
        sequenceNumber = s;
    }

    /**
     * Set the type of packet that this is.
     *
     * @param t packet type
     */
    public void setPacketType(int t) {
        packetType = t;
    }

    /**
     * Set the CRC of this packet.
     *
     * @param c CRC value
     */
    public void setCrcNum(int c) {
        crcNum = c;
    }

    /**
     * Set the matching CRC value.
     *
     * @param v true if the CRCs match, false otherwise
     */
    public void setMatchingCRC(boolean v) {
        isMatchingCRC = v;
    }

    /**
     * Get the CRC number.
     *
     * @return CRC in number format
     */
    public long getCrcNum() {
        return crcNum;
    }

    /**
     * Convert the timestamp byte array to a readable long.
     *
     * @return long form timestamp
     */
    public long convertToTime() {
        // Bit shift
        return ((long) data[0] & 0xFF) << 56 | ((long) data[1] & 0xFF) << 48 | ((long) data[2] & 0xFF) << 40 | ((long) data[3] & 0xFF) << 32 | ((long) data[4] & 0xFF) << 24 | ((long) data[5] & 0xFF) << 16 | ((long) data[6] & 0xFF) << 8 | ((long) data[7] & 0xFF);
    }
}
